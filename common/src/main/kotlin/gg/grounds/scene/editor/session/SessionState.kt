package gg.grounds.scene.editor.session

import gg.grounds.scene.editor.history.SceneHistory
import gg.grounds.scene.editor.repository.SceneFingerprint
import gg.grounds.scene.editor.validation.SaveEligibility
import gg.grounds.scene.editor.validation.SceneValidationState
import gg.grounds.scene.format.SceneDocument
import java.util.UUID

/** Immutable state published by a session. The selection map is a defensive copy. */
class SessionState(
    val document: SceneDocument,
    baseCanonicalBytes: ByteArray,
    val history: SceneHistory,
    selections: Map<UUID, EditorSelection>,
    val generation: Long,
    val validation: SceneValidationState,
    val saveEligibility: SaveEligibility,
    val baseFingerprint: SceneFingerprint,
) {
    /** Array and map are copied at the state boundary so callers cannot alter session state. */
    private val baseCanonicalBytes: ByteArray = baseCanonicalBytes.copyOf()
    val selections: Map<UUID, EditorSelection> = selections.toMap()

    fun baseCanonicalBytes(): ByteArray = baseCanonicalBytes.copyOf()

    fun matchesBaseCanonicalBytes(bytes: ByteArray): Boolean =
        baseCanonicalBytes.contentEquals(bytes)

    fun copy(
        document: SceneDocument = this.document,
        baseCanonicalBytes: ByteArray = this.baseCanonicalBytes,
        history: SceneHistory = this.history,
        selections: Map<UUID, EditorSelection> = this.selections,
        generation: Long = this.generation,
        validation: SceneValidationState = this.validation,
        saveEligibility: SaveEligibility = this.saveEligibility,
        baseFingerprint: SceneFingerprint = this.baseFingerprint,
    ): SessionState =
        SessionState(
            document,
            baseCanonicalBytes,
            history,
            selections,
            generation,
            validation,
            saveEligibility,
            baseFingerprint,
        )
}
