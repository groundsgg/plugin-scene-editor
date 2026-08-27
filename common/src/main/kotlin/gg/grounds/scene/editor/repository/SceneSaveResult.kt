package gg.grounds.scene.editor.repository

sealed interface SceneSaveResult {
    class Saved internal constructor(val fingerprint: SceneFingerprint.Present) : SceneSaveResult

    data object FingerprintConflict : SceneSaveResult

    data object StaleGeneration : SceneSaveResult

    data object AtomicMoveUnsupported : SceneSaveResult

    data object FileTooLarge : SceneSaveResult

    data object EncodingFailure : SceneSaveResult

    data object NoSession : SceneSaveResult

    data object SaveInProgress : SceneSaveResult

    data object Ineligible : SceneSaveResult

    data class Rejected(val reason: PathRejection) : SceneSaveResult

    data class IoFailure(val message: String) : SceneSaveResult
}
