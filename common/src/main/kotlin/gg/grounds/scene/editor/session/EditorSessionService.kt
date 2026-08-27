package gg.grounds.scene.editor.session

import gg.grounds.scene.editor.SceneEditStatus
import gg.grounds.scene.editor.SceneEditorEvent
import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.history.SceneHistory
import gg.grounds.scene.editor.lease.ElementLeaseRegistry
import gg.grounds.scene.editor.lease.LeaseAcquisition
import gg.grounds.scene.editor.mutation.SceneMutation
import gg.grounds.scene.editor.mutation.SceneMutationRejection
import gg.grounds.scene.editor.mutation.SceneMutationResult
import gg.grounds.scene.editor.validation.SaveEligibility
import gg.grounds.scene.editor.validation.SceneValidationState
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneEncodeResult
import gg.grounds.scene.format.SceneJson
import java.time.Clock
import java.util.ArrayDeque
import java.util.UUID

/** Serializes every state transition under one monitor and exposes only immutable snapshots. */
class EditorSessionService
private constructor(
    private val catalogs: SceneCatalogBinding,
    private val leases: ElementLeaseRegistry,
    private val eventErrorHandler: (Throwable) -> Unit,
    @Suppress("UNUSED_PARAMETER") private val constructionToken: Unit,
) : SceneEditStatus {
    private val sessions = linkedMapOf<UUID, EditorSession>()
    private val listeners = linkedSetOf<(SceneEditorEvent) -> Unit>()
    private val eventQueue = ArrayDeque<QueuedEvent>()
    private var drainingEvents = false

    constructor(
        catalogs: SceneCatalogBinding,
        clock: Clock = Clock.systemUTC(),
        eventErrorHandler: (Throwable) -> Unit = {},
    ) : this(catalogs, ElementLeaseRegistry(clock), eventErrorHandler, Unit)

    internal constructor(
        catalogs: SceneCatalogBinding,
        leases: ElementLeaseRegistry,
        eventErrorHandler: (Throwable) -> Unit = {},
    ) : this(catalogs, leases, eventErrorHandler, Unit)

    @Synchronized
    fun open(worldId: UUID, document: SceneDocument): SessionOpenResult {
        sessions[worldId]?.let {
            return SessionOpenResult.Opened(it)
        }
        val canonical = canonical(document) ?: return SessionOpenResult.EncodingFailure
        val validation = SceneValidationState.of(document, catalogs)
        val state =
            SessionState(
                document,
                canonical,
                SceneHistory.empty(),
                emptyMap(),
                0,
                validation,
                SaveEligibility.from(validation),
            )
        return SessionOpenResult.Opened(
            EditorSession(worldId, state).also { sessions[worldId] = it }
        )
    }

    @Synchronized fun session(worldId: UUID): EditorSession? = sessions[worldId]

    @Synchronized
    fun addListener(listener: (SceneEditorEvent) -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun removeListener(listener: (SceneEditorEvent) -> Unit): Boolean = listeners.remove(listener)

    @Synchronized
    fun select(worldId: UUID, playerId: UUID, elementId: LocalId): SelectionResult =
        select(worldId, playerId, elementId, false)

    @Synchronized
    fun overrideSelection(worldId: UUID, playerId: UUID, elementId: LocalId): SelectionResult =
        select(worldId, playerId, elementId, true)

    private fun select(
        worldId: UUID,
        playerId: UUID,
        elementId: LocalId,
        override: Boolean,
    ): SelectionResult {
        val session = sessions[worldId] ?: return SelectionResult.NoSession
        if (session.document.elements.none { it.id == elementId })
            return SelectionResult.ElementNotFound
        val selected =
            if (override) {
                SelectionResult.Selected(
                    leases.override(worldId, elementId, playerId).previousOwner
                )
            } else
                when (val acquired = leases.acquire(worldId, elementId, playerId)) {
                    is LeaseAcquisition.Acquired -> SelectionResult.Selected(null)
                    is LeaseAcquisition.Refused -> return SelectionResult.Refused(acquired.owner)
                }
        val previous = session.state.selections[playerId]
        if (previous?.elementId != elementId)
            previous?.let { leases.release(worldId, it.elementId, playerId) }
        val displaced = selected.previousOwner
        val retained =
            if (displaced == null) session.state.selections
            else
                session.state.selections.filterNot { (player, selection) ->
                    player == displaced && selection.elementId == elementId
                }
        session.state =
            session.state.copy(selections = retained + (playerId to EditorSelection(elementId)))
        return selected
    }

    @Synchronized
    fun selection(worldId: UUID, playerId: UUID): EditorSelection? =
        sessions[worldId]?.state?.selections?.get(playerId)

    @Synchronized
    fun deselect(worldId: UUID, playerId: UUID) {
        val session = sessions[worldId] ?: return
        session.state.selections[playerId]?.let { leases.release(worldId, it.elementId, playerId) }
        session.state = session.state.copy(selections = session.state.selections - playerId)
    }

    @Synchronized
    fun releasePlayer(worldId: UUID, playerId: UUID) {
        deselect(worldId, playerId)
        leases.releasePlayer(worldId, playerId)
    }

    fun mutate(worldId: UUID, mutation: SceneMutation): MutationOutcome {
        val outcome =
            synchronized(this) {
                val session = sessions[worldId] ?: return@synchronized MutationOutcome.NoSession
                mutation.target?.let { target ->
                    val selected =
                        session.state.selections[mutation.actor]?.elementId
                            ?: return@synchronized rejected(
                                session.document,
                                SceneMutationRejection.SELECTION_REQUIRED,
                            )
                    if (selected != target)
                        return@synchronized rejected(
                            session.document,
                            SceneMutationRejection.SELECTION_MISMATCH,
                        )
                    val lease =
                        leases.lease(worldId, target).orElse(null)
                            ?: return@synchronized rejected(
                                session.document,
                                SceneMutationRejection.LEASE_REQUIRED,
                            )
                    if (lease.owner != mutation.actor)
                        return@synchronized rejected(
                            session.document,
                            SceneMutationRejection.LEASE_HELD_BY_OTHER,
                        )
                }
                when (val result = mutation.apply(session.document, catalogs)) {
                    is SceneMutationResult.Rejected -> rejected(session.document, result.reason)
                    is SceneMutationResult.Success -> {
                        transition(
                            session,
                            result.document,
                            session.history.record(session.document),
                        )
                        mutation.target?.let { leases.renew(worldId, it, mutation.actor) }
                        if (mutation.name == "element.remove")
                            mutation.target?.let { deleted ->
                                leases.releaseElement(worldId, deleted)
                                session.state =
                                    session.state.copy(
                                        selections =
                                            session.state.selections.filterValues {
                                                it.elementId != deleted
                                            }
                                    )
                            }
                        eventQueue.addLast(
                            QueuedEvent(
                                SceneEditorEvent(
                                    worldId,
                                    mutation.actor,
                                    mutation.name,
                                    mutation.target,
                                ),
                                listeners.toList(),
                            )
                        )
                        MutationOutcome.Applied(result)
                    }
                }
            }
        drainEvents()
        return outcome
    }

    @Synchronized fun undo(worldId: UUID): Boolean = undo(worldId, 1)

    @Synchronized fun redo(worldId: UUID): Boolean = redo(worldId, 1)

    @Synchronized
    fun undo(worldId: UUID, steps: Int): Boolean {
        if (steps <= 0) return false
        val session = sessions[worldId] ?: return false
        val transition = session.history.undo(session.document, steps) ?: return false
        transition(session, transition.document, transition.history)
        return true
    }

    @Synchronized
    fun redo(worldId: UUID, steps: Int): Boolean {
        if (steps <= 0) return false
        val session = sessions[worldId] ?: return false
        val transition = session.history.redo(session.document, steps) ?: return false
        transition(session, transition.document, transition.history)
        return true
    }

    @Synchronized
    fun prepareSave(worldId: UUID): SaveSnapshotResult {
        val session = sessions[worldId] ?: return SaveSnapshotResult.NoSession
        val bytes = canonical(session.document) ?: return SaveSnapshotResult.EncodingFailure
        return SaveSnapshotResult.Prepared(
            SaveSnapshot(worldId, session.state.generation, session.document, bytes)
        )
    }

    @Synchronized
    internal fun confirmPersisted(snapshot: SaveSnapshot): Boolean {
        val session = sessions[snapshot.worldId] ?: return false
        val current = canonical(session.document) ?: return false
        if (
            session.state.generation != snapshot.generation ||
                !snapshot.matchesCanonicalBytes(current)
        )
            return false
        session.state = session.state.copy(baseCanonicalBytes = snapshot.copyCanonicalBytes())
        return true
    }

    @Synchronized
    override fun hasUnsavedChanges(worldId: UUID): Boolean {
        val session = sessions[worldId] ?: return false
        val current = canonical(session.document) ?: return true
        return !session.state.matchesBaseCanonicalBytes(current)
    }

    private fun transition(session: EditorSession, document: SceneDocument, history: SceneHistory) {
        val validation = SceneValidationState.of(document, catalogs)
        session.state =
            session.state.copy(
                document = document,
                history = history,
                generation = session.state.generation + 1,
                validation = validation,
                saveEligibility = SaveEligibility.from(validation),
            )
    }

    private fun rejected(document: SceneDocument, reason: SceneMutationRejection) =
        MutationOutcome.Rejected(SceneMutationResult.Rejected(document, reason))

    private fun canonical(document: SceneDocument): ByteArray? =
        (SceneJson.encode(document) as? SceneEncodeResult.Success)?.bytes

    private fun drainEvents() {
        synchronized(this) {
            if (drainingEvents) return
            drainingEvents = true
        }
        while (true) {
            val queued =
                synchronized(this) {
                    if (eventQueue.isEmpty()) {
                        drainingEvents = false
                        null
                    } else eventQueue.removeFirst()
                }
            if (queued == null) return
            queued.listeners.forEach { listener ->
                try {
                    listener(queued.event)
                } catch (error: Throwable) {
                    try {
                        eventErrorHandler(error)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private data class QueuedEvent(
        val event: SceneEditorEvent,
        val listeners: List<(SceneEditorEvent) -> Unit>,
    )

    sealed interface MutationOutcome {
        val accepted: Boolean
        val result: SceneMutationResult?

        data class Applied(override val result: SceneMutationResult.Success) : MutationOutcome {
            override val accepted = true
        }

        data class Rejected(override val result: SceneMutationResult.Rejected) : MutationOutcome {
            override val accepted = false
        }

        data object NoSession : MutationOutcome {
            override val accepted = false
            override val result: SceneMutationResult? = null
        }
    }
}

sealed interface SessionOpenResult {
    data class Opened(val session: EditorSession) : SessionOpenResult

    data object EncodingFailure : SessionOpenResult
}

sealed interface SelectionResult {
    data class Selected(val previousOwner: UUID?) : SelectionResult

    data class Refused(val owner: UUID) : SelectionResult

    data object ElementNotFound : SelectionResult

    data object NoSession : SelectionResult
}

class SaveSnapshot
internal constructor(
    val worldId: UUID,
    val generation: Long,
    val document: SceneDocument,
    bytes: ByteArray,
) {
    private val bytes: ByteArray = bytes.copyOf()
    val canonicalBytes: ByteArray
        get() = bytes.copyOf()

    internal fun matchesCanonicalBytes(candidate: ByteArray): Boolean =
        bytes.contentEquals(candidate)

    internal fun copyCanonicalBytes(): ByteArray = bytes.copyOf()
}

sealed interface SaveSnapshotResult {
    data class Prepared(val snapshot: SaveSnapshot) : SaveSnapshotResult

    data object NoSession : SaveSnapshotResult

    data object EncodingFailure : SaveSnapshotResult
}
