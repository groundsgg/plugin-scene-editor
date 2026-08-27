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
import gg.grounds.scene.editor.repository.SceneFingerprint
import gg.grounds.scene.editor.repository.SceneLoadResult
import gg.grounds.scene.editor.repository.SceneSaveResult
import gg.grounds.scene.editor.repository.WorldSceneRepository
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
    private val saveReservations = linkedMapOf<UUID, SaveReservation>()

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
        return open(worldId, document, SceneFingerprint.Absent)
    }

    @Synchronized
    fun open(
        worldId: UUID,
        document: SceneDocument,
        baseFingerprint: SceneFingerprint,
    ): SessionOpenResult {
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
                baseFingerprint,
            )
        return SessionOpenResult.Opened(
            EditorSession(worldId, state).also { sessions[worldId] = it }
        )
    }

    @Synchronized fun session(worldId: UUID): EditorSession? = sessions[worldId]

    /** Immutable validation snapshot for adapters; session state itself never escapes common. */
    @Synchronized
    fun validation(worldId: UUID): SceneValidationState? = sessions[worldId]?.state?.validation

    /**
     * Replaces a world session only from a successfully decoded canonical scene. Dirty work is
     * never discarded implicitly; adapters must pass the literal confirmed policy after their
     * player-facing confirmation has completed.
     */
    @Synchronized
    fun replaceFromLoad(
        worldId: UUID,
        load: SceneLoadResult,
        policy: ReloadPolicy = ReloadPolicy.CLEAN_ONLY,
    ): SessionReloadResult {
        val session = sessions[worldId] ?: return SessionReloadResult.NoSession
        if (saveReservations.containsKey(worldId)) return SessionReloadResult.SaveInProgress
        val loaded =
            load as? SceneLoadResult.Loaded ?: return SessionReloadResult.LoadUnavailable(load)
        val dirty = hasUnsavedChanges(worldId)
        if (dirty && policy != ReloadPolicy.CONFIRMED_DISCARD)
            return SessionReloadResult.DiscardConfirmationRequired
        val audit =
            if (dirty)
                DiscardAudit(
                    worldId = worldId,
                    discardedGeneration = session.state.generation,
                    discardedFingerprint = session.state.baseFingerprint,
                )
            else null
        leases.releaseWorld(worldId)
        val validation = SceneValidationState.of(loaded.document, catalogs)
        session.state =
            SessionState(
                document = loaded.document,
                baseCanonicalBytes = loaded.canonicalBytes,
                history = SceneHistory.empty(),
                selections = emptyMap(),
                generation = session.state.generation + 1,
                validation = validation,
                saveEligibility = SaveEligibility.from(validation),
                baseFingerprint = loaded.fingerprint,
            )
        return SessionReloadResult.Reloaded(session, audit)
    }

    @Synchronized
    fun leaseStatus(worldId: UUID, elementId: LocalId): LeaseStatusResult {
        val session = sessions[worldId] ?: return LeaseStatusResult.NoSession
        if (session.document.elements.none { it.id == elementId })
            return LeaseStatusResult.ElementNotFound
        val lease =
            leases.lease(worldId, elementId).orElse(null) ?: return LeaseStatusResult.Available
        return LeaseStatusResult.Held(lease)
    }

    /** Administrative release only; authorization belongs to the Paper adapter. */
    @Synchronized
    fun releaseLease(worldId: UUID, elementId: LocalId): LeaseReleaseResult {
        val session = sessions[worldId] ?: return LeaseReleaseResult.NoSession
        if (session.document.elements.none { it.id == elementId })
            return LeaseReleaseResult.ElementNotFound
        val lease =
            leases.lease(worldId, elementId).orElse(null) ?: return LeaseReleaseResult.NotHeld
        leases.releaseElement(worldId, elementId)
        session.state =
            session.state.copy(
                selections = session.state.selections.filterValues { it.elementId != elementId }
            )
        return LeaseReleaseResult.Released(lease)
    }

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
                if (saveReservations.containsKey(worldId))
                    return@synchronized rejected(
                        session.document,
                        SceneMutationRejection.SAVE_IN_PROGRESS,
                    )
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
        if (saveReservations.containsKey(worldId)) return false
        val transition = session.history.undo(session.document, steps) ?: return false
        transition(session, transition.document, transition.history)
        return true
    }

    @Synchronized
    fun redo(worldId: UUID, steps: Int): Boolean {
        if (steps <= 0) return false
        val session = sessions[worldId] ?: return false
        if (saveReservations.containsKey(worldId)) return false
        val transition = session.history.redo(session.document, steps) ?: return false
        transition(session, transition.document, transition.history)
        return true
    }

    /**
     * Persists without holding the session monitor during file I/O. A reservation freezes document
     * transitions for this world until the repository result is reconciled.
     */
    fun save(worldId: UUID, repository: WorldSceneRepository): SceneSaveResult {
        val reservation = synchronized(this) { beginSave(worldId) }
        if (reservation !is SaveReservationResult.Reserved) return reservation.toSaveResult()
        return try {
            val result = repository.save(reservation.reservation)
            synchronized(this) { finishSave(reservation.reservation, result) }
        } catch (error: Throwable) {
            synchronized(this) {
                finishSave(
                    reservation.reservation,
                    SceneSaveResult.IoFailure(error.message ?: "save"),
                )
            }
            if (error !is Exception) throw error
            SceneSaveResult.IoFailure(error.message ?: "save")
        }
    }

    /**
     * Package-internal protocol endpoint for repositories and common tests, never a Paper bypass.
     */
    @Synchronized
    private fun beginSave(worldId: UUID): SaveReservationResult {
        val session = sessions[worldId] ?: return SaveReservationResult.NoSession
        if (saveReservations.containsKey(worldId)) return SaveReservationResult.SaveInProgress
        if (!session.state.saveEligibility.isEligible)
            return SaveReservationResult.Ineligible(session.state.saveEligibility)
        val bytes = canonical(session.document) ?: return SaveReservationResult.EncodingFailure
        val token = UUID.randomUUID()
        val reservation =
            SaveReservation(
                this,
                token,
                worldId,
                session.state.generation,
                session.document,
                bytes,
                session.state.baseFingerprint,
            )
        saveReservations[worldId] = reservation
        return SaveReservationResult.Reserved(reservation)
    }

    @Synchronized
    private fun finishSave(reservation: SaveReservation, result: SceneSaveResult): SceneSaveResult {
        if (!reservation.isActive()) return result
        saveReservations.remove(reservation.worldId)
        val session = sessions[reservation.worldId] ?: return result
        if (result !is SceneSaveResult.Saved) return result
        val current = canonical(session.document) ?: return SceneSaveResult.EncodingFailure
        if (
            session.state.generation != reservation.generation ||
                !reservation.matchesCanonicalBytes(current) ||
                result.fingerprint != SceneFingerprint.of(reservation.copyCanonicalBytes())
        )
            return SceneSaveResult.StaleGeneration
        session.state =
            session.state.copy(
                baseCanonicalBytes = reservation.copyCanonicalBytes(),
                baseFingerprint = result.fingerprint,
            )
        return result
    }

    @Synchronized
    private fun isReservationActive(reservation: SaveReservation): Boolean =
        saveReservations[reservation.worldId] === reservation && reservation.matchesOwner(this)

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

    /** Capability name is public for the repository boundary; its constructor is not. */
    class SaveReservation
    internal constructor(
        private val owner: EditorSessionService,
        private val token: UUID,
        val worldId: UUID,
        val generation: Long,
        val document: SceneDocument,
        bytes: ByteArray,
        val expectedFingerprint: SceneFingerprint,
    ) {
        private val bytes = bytes.copyOf()

        internal fun matchesCanonicalBytes(candidate: ByteArray) = bytes.contentEquals(candidate)

        internal fun copyCanonicalBytes() = bytes.copyOf()

        internal fun isActive() = owner.isReservationActive(this)

        internal fun matchesOwner(candidate: EditorSessionService) = owner === candidate
    }
}

sealed interface SessionOpenResult {
    data class Opened(val session: EditorSession) : SessionOpenResult

    data object EncodingFailure : SessionOpenResult
}

/** The only common-layer authorization to discard local dirty work. */
enum class ReloadPolicy {
    CLEAN_ONLY,
    CONFIRMED_DISCARD,
}

data class DiscardAudit(
    val worldId: UUID,
    val discardedGeneration: Long,
    val discardedFingerprint: SceneFingerprint,
)

sealed interface SessionReloadResult {
    data class Reloaded(val session: EditorSession, val discardAudit: DiscardAudit?) :
        SessionReloadResult

    data object NoSession : SessionReloadResult

    data object SaveInProgress : SessionReloadResult

    data object DiscardConfirmationRequired : SessionReloadResult

    data class LoadUnavailable(val load: SceneLoadResult) : SessionReloadResult
}

sealed interface LeaseStatusResult {
    data class Held(val lease: gg.grounds.scene.editor.lease.ElementLease) : LeaseStatusResult

    data object Available : LeaseStatusResult

    data object ElementNotFound : LeaseStatusResult

    data object NoSession : LeaseStatusResult
}

sealed interface LeaseReleaseResult {
    data class Released(val lease: gg.grounds.scene.editor.lease.ElementLease) : LeaseReleaseResult

    data object NotHeld : LeaseReleaseResult

    data object ElementNotFound : LeaseReleaseResult

    data object NoSession : LeaseReleaseResult
}

sealed interface SelectionResult {
    data class Selected(val previousOwner: UUID?) : SelectionResult

    data class Refused(val owner: UUID) : SelectionResult

    data object ElementNotFound : SelectionResult

    data object NoSession : SelectionResult
}

/** Opaque, token-bound save capability. Only common code can construct or complete one. */
sealed interface SaveReservationResult {
    data class Reserved(internal val reservation: EditorSessionService.SaveReservation) :
        SaveReservationResult

    data object NoSession : SaveReservationResult

    data object SaveInProgress : SaveReservationResult

    data class Ineligible(val eligibility: SaveEligibility) : SaveReservationResult

    data object EncodingFailure : SaveReservationResult

    fun toSaveResult(): SceneSaveResult =
        when (this) {
            NoSession -> SceneSaveResult.NoSession
            SaveInProgress -> SceneSaveResult.SaveInProgress
            is Ineligible -> SceneSaveResult.Ineligible
            EncodingFailure -> SceneSaveResult.EncodingFailure
            is Reserved -> error("A reservation is not a save result")
        }
}
