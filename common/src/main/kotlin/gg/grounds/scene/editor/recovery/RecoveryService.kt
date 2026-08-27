package gg.grounds.scene.editor.recovery

import gg.grounds.scene.editor.repository.AtomicSceneFileStore
import gg.grounds.scene.editor.repository.ConditionalMoveResult
import gg.grounds.scene.editor.repository.NioAtomicSceneFileStore
import gg.grounds.scene.editor.repository.SceneFingerprint
import gg.grounds.scene.editor.repository.SceneLoadResult
import gg.grounds.scene.editor.repository.WorldSceneRepository
import gg.grounds.scene.editor.repository.bestEffortTempCleanup
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneEncodeResult
import gg.grounds.scene.format.SceneJson
import gg.grounds.scene.format.SceneValidation
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Explicit recovery only; generated names prevent user-controlled output paths. */
class RecoveryService(
    private val repository: WorldSceneRepository,
    private val pluginDataRoot: Path,
    private val clock: Clock = Clock.systemUTC(),
    private val files: AtomicSceneFileStore = NioAtomicSceneFileStore,
) {
    fun export(document: SceneDocument): RecoveryExportResult = exportCanonical(document)

    private fun exportCanonical(document: SceneDocument): RecoveryExportResult {
        if (SceneValidation.validateIntrinsic(document).problems.isNotEmpty())
            return RecoveryExportResult.IntrinsicInvalid
        val canonicalBytes =
            (SceneJson.encode(document) as? SceneEncodeResult.Success)?.bytes
                ?: return RecoveryExportResult.EncodingFailure
        val dataRoot = pluginDataRoot.toAbsolutePath().normalize()
        if (
            Files.isSymbolicLink(dataRoot) ||
                !Files.isDirectory(dataRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        )
            return RecoveryExportResult.RejectedRoot
        val root = dataRoot.resolve("scene-diagnostics").normalize()
        if (!root.startsWith(dataRoot) || Files.isSymbolicLink(root))
            return RecoveryExportResult.RejectedRoot
        try {
            Files.createDirectory(root)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            if (
                Files.isSymbolicLink(root) ||
                    !Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            )
                return RecoveryExportResult.RejectedRoot
        } catch (_: Exception) {
            return RecoveryExportResult.IoFailure
        }
        val output =
            root.resolve(
                "scene-${DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(java.time.ZoneOffset.UTC).format(clock.instant())}-${UUID.randomUUID()}.json"
            )
        if (!isSafeDiagnosticsRoot(dataRoot, root)) return RecoveryExportResult.RejectedRoot
        val temp =
            try {
                files.createTempSibling(output)
            } catch (_: Exception) {
                return RecoveryExportResult.IoFailure
            }
        try {
            files.writeAndFlush(temp, canonicalBytes)
            if (!isSafeDiagnosticsRoot(dataRoot, root)) {
                bestEffortTempCleanup(files, temp)
                return RecoveryExportResult.RejectedRoot
            }
            when (
                files.atomicMoveIfUnchanged(
                    temp,
                    output,
                    SceneFingerprint.Absent,
                    canonicalBytes.size,
                )
            ) {
                ConditionalMoveResult.Moved -> Unit
                ConditionalMoveResult.Unsupported -> {
                    bestEffortTempCleanup(files, temp)
                    return RecoveryExportResult.AtomicMoveUnsupported
                }
                ConditionalMoveResult.Conflict,
                ConditionalMoveResult.Rejected -> {
                    bestEffortTempCleanup(files, temp)
                    return RecoveryExportResult.IoFailure
                }
            }
        } catch (_: Exception) {
            bestEffortTempCleanup(files, temp)
            return RecoveryExportResult.IoFailure
        }
        return RecoveryExportResult.Exported(
            RecoveryExport(output, canonicalBytes, SceneFingerprint.of(canonicalBytes))
        )
    }

    private fun isSafeDiagnosticsRoot(dataRoot: Path, root: Path): Boolean =
        root.startsWith(dataRoot) &&
            !Files.isSymbolicLink(dataRoot) &&
            Files.isDirectory(dataRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(root) &&
            Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)

    fun reload(dirty: Boolean): RecoveryReloadResult {
        if (dirty) return RecoveryReloadResult.DirtyRefused
        return RecoveryReloadResult.Loaded(repository.load())
    }

    fun discardAndReload(
        literalConfirmation: String,
        dirtySceneId: String?,
        dirtyFingerprint: SceneFingerprint?,
    ): RecoveryReloadResult {
        if (literalConfirmation != "confirm") return RecoveryReloadResult.ConfirmationRequired
        return RecoveryReloadResult.Discarded(
            repository.load(),
            RecoveryAudit(dirtySceneId, dirtyFingerprint),
        )
    }
}

class RecoveryExport(path: Path, bytes: ByteArray, val fingerprint: SceneFingerprint) {
    val path: Path = path
    private val canonicalBytes: ByteArray = bytes.copyOf()
    val bytes: ByteArray
        get() = canonicalBytes.copyOf()
}

sealed interface RecoveryExportResult {
    data class Exported(val export: RecoveryExport) : RecoveryExportResult

    data object IntrinsicInvalid : RecoveryExportResult

    data object EncodingFailure : RecoveryExportResult

    data object RejectedRoot : RecoveryExportResult

    data object AtomicMoveUnsupported : RecoveryExportResult

    data object IoFailure : RecoveryExportResult
}

data class RecoveryAudit(val discardedSceneId: String?, val discardedFingerprint: SceneFingerprint?)

sealed interface RecoveryReloadResult {
    data object DirtyRefused : RecoveryReloadResult

    data object ConfirmationRequired : RecoveryReloadResult

    data class Loaded(val load: SceneLoadResult) : RecoveryReloadResult

    data class Discarded(val load: SceneLoadResult, val audit: RecoveryAudit) : RecoveryReloadResult
}
