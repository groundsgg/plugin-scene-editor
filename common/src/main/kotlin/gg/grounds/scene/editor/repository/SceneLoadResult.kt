package gg.grounds.scene.editor.repository

import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneProblem

sealed interface SceneLoadResult {
    data object Absent : SceneLoadResult

    class Loaded(
        val document: SceneDocument,
        val fingerprint: SceneFingerprint.Present,
        canonicalBytes: ByteArray,
    ) : SceneLoadResult {
        private val bytes = canonicalBytes.copyOf()
        val canonicalBytes: ByteArray
            get() = bytes.copyOf()
    }

    class Invalid(
        bytes: ByteArray,
        val problems: List<SceneProblem>,
        val fingerprint: SceneFingerprint.Present,
    ) : SceneLoadResult {
        private val source = bytes.copyOf()
        val bytes: ByteArray
            get() = source.copyOf()
    }

    data class Rejected(val reason: PathRejection) : SceneLoadResult
}

enum class PathRejection {
    ROOT_NOT_DIRECTORY,
    SCENE_SYMLINK,
    SCENE_ESCAPES_ROOT,
    FILE_TOO_LARGE,
    IO_FAILURE,
}
