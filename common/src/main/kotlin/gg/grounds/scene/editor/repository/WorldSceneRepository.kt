package gg.grounds.scene.editor.repository

import gg.grounds.scene.editor.session.EditorSessionService.SaveReservation
import gg.grounds.scene.format.SceneDecodeResult
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneEncodeResult
import gg.grounds.scene.format.SceneJson
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Per-world synchronized, exact-root repository using SceneJson as its sole codec. */
class WorldSceneRepository(
    worldRoot: Path,
    private val files: AtomicSceneFileStore = NioAtomicSceneFileStore,
    private val maxBytes: Int = 2 * 1024 * 1024,
    private val clock: Clock = Clock.systemUTC(),
) : SceneRepository {
    init {
        require(maxBytes in 1..(16 * 1024 * 1024)) { "maxBytes must be between 1 and 16 MiB" }
    }

    private val root = worldRoot.toAbsolutePath().normalize()
    private val scene = root.resolve("scene.json").normalize()

    @Synchronized
    override fun load(): SceneLoadResult {
        val rejection = pathRejection()
        if (rejection != null) return SceneLoadResult.Rejected(rejection)
        val bytes =
            try {
                files.readRegularNoFollowBounded(scene, maxBytes)
            } catch (_: java.nio.file.NoSuchFileException) {
                return SceneLoadResult.Absent
            } catch (_: FileTooLargeException) {
                return SceneLoadResult.Rejected(PathRejection.FILE_TOO_LARGE)
            } catch (_: SymbolicLinkRejectedException) {
                return SceneLoadResult.Rejected(PathRejection.SCENE_SYMLINK)
            } catch (_: Exception) {
                return SceneLoadResult.Rejected(PathRejection.IO_FAILURE)
            }
        val fingerprint = SceneFingerprint.of(bytes) as SceneFingerprint.Present
        return when (val decoded = SceneJson.decode(bytes)) {
            is SceneDecodeResult.Success ->
                when (val encoded = SceneJson.encode(decoded.scene)) {
                    is SceneEncodeResult.Success ->
                        SceneLoadResult.Loaded(decoded.scene, fingerprint, encoded.bytes)
                    is SceneEncodeResult.Failure ->
                        SceneLoadResult.Invalid(bytes.copyOf(), encoded.problems, fingerprint)
                }
            is SceneDecodeResult.Failure ->
                SceneLoadResult.Invalid(bytes.copyOf(), decoded.problems, fingerprint)
        }
    }

    private fun saveCanonical(
        expected: SceneFingerprint,
        canonicalBytes: ByteArray,
    ): SceneSaveResult {
        pathRejection()?.let {
            return SceneSaveResult.Rejected(it)
        }
        if (canonicalBytes.size > maxBytes) return SceneSaveResult.FileTooLarge
        val current =
            currentFingerprint() ?: return SceneSaveResult.Rejected(PathRejection.IO_FAILURE)
        if (current != expected) return SceneSaveResult.FingerprintConflict
        val temp =
            try {
                files.createTempSibling(scene)
            } catch (e: Exception) {
                return SceneSaveResult.IoFailure(e.message ?: "temp")
            }
        try {
            files.writeAndFlush(temp, canonicalBytes)
            when (files.atomicMoveIfUnchanged(temp, scene, expected, maxBytes)) {
                ConditionalMoveResult.Moved -> Unit
                ConditionalMoveResult.Conflict -> {
                    bestEffortTempCleanup(files, temp)
                    return SceneSaveResult.FingerprintConflict
                }
                ConditionalMoveResult.Unsupported -> {
                    bestEffortTempCleanup(files, temp)
                    return SceneSaveResult.AtomicMoveUnsupported
                }
                ConditionalMoveResult.Rejected -> {
                    bestEffortTempCleanup(files, temp)
                    return SceneSaveResult.IoFailure("move")
                }
            }
        } catch (e: Exception) {
            bestEffortTempCleanup(files, temp)
            return SceneSaveResult.IoFailure(e.message ?: "write")
        }
        return SceneSaveResult.Saved(
            SceneFingerprint.of(canonicalBytes) as SceneFingerprint.Present
        )
    }

    /** The reservation predicate is checked immediately before replacement, after temp flush. */
    @Synchronized
    override fun save(reservation: SaveReservation): SceneSaveResult {
        return save(
            reservation.expectedFingerprint,
            reservation.copyCanonicalBytes(),
            { reservation.isActive() },
        )
    }

    private fun save(
        expected: SceneFingerprint,
        canonicalBytes: ByteArray,
        stillCurrent: () -> Boolean,
    ): SceneSaveResult {
        pathRejection()?.let {
            return SceneSaveResult.Rejected(it)
        }
        if (canonicalBytes.size > maxBytes) return SceneSaveResult.FileTooLarge
        val current =
            currentFingerprint() ?: return SceneSaveResult.Rejected(PathRejection.IO_FAILURE)
        if (current != expected) return SceneSaveResult.FingerprintConflict
        val temp =
            try {
                files.createTempSibling(scene)
            } catch (e: Exception) {
                return SceneSaveResult.IoFailure(e.message ?: "temp")
            }
        try {
            files.writeAndFlush(temp, canonicalBytes)
            if (!stillCurrent()) {
                bestEffortTempCleanup(files, temp)
                return SceneSaveResult.StaleGeneration
            }
            when (files.atomicMoveIfUnchanged(temp, scene, expected, maxBytes)) {
                ConditionalMoveResult.Moved -> Unit
                ConditionalMoveResult.Conflict -> {
                    bestEffortTempCleanup(files, temp)
                    return SceneSaveResult.FingerprintConflict
                }
                ConditionalMoveResult.Unsupported -> {
                    bestEffortTempCleanup(files, temp)
                    return SceneSaveResult.AtomicMoveUnsupported
                }
                ConditionalMoveResult.Rejected -> {
                    bestEffortTempCleanup(files, temp)
                    return SceneSaveResult.IoFailure("move")
                }
            }
        } catch (e: Exception) {
            bestEffortTempCleanup(files, temp)
            return SceneSaveResult.IoFailure(e.message ?: "write")
        }
        return SceneSaveResult.Saved(
            SceneFingerprint.of(canonicalBytes) as SceneFingerprint.Present
        )
    }

    fun scenePath(): Path = scene

    /** Invalid bytes are first moved byte-for-byte to a generated sibling backup. */
    @Synchronized
    fun backupInvalidAndCreate(
        expectedInvalid: SceneFingerprint.Present,
        document: SceneDocument,
    ): RecoveryCreateResult {
        if (pathRejection() != null) return RecoveryCreateResult.Rejected
        val current =
            currentFingerprint() as? SceneFingerprint.Present
                ?: return RecoveryCreateResult.Conflict
        if (current != expectedInvalid) return RecoveryCreateResult.Conflict
        val source =
            try {
                files.readRegularNoFollowBounded(scene, maxBytes)
            } catch (_: FileTooLargeException) {
                return RecoveryCreateResult.Rejected
            } catch (_: Exception) {
                return RecoveryCreateResult.Rejected
            }
        if (SceneFingerprint.of(source) != expectedInvalid) return RecoveryCreateResult.Conflict
        val decoded = SceneJson.decode(source)
        if (decoded !is SceneDecodeResult.Failure) return RecoveryCreateResult.Conflict
        if (
            gg.grounds.scene.format.SceneValidation.validateIntrinsic(document)
                .problems
                .isNotEmpty()
        )
            return RecoveryCreateResult.IntrinsicInvalid
        val canonicalBytes =
            (SceneJson.encode(document) as? SceneEncodeResult.Success)?.bytes
                ?: return RecoveryCreateResult.EncodingFailure
        val backup =
            scene.resolveSibling(
                "scene.json.invalid-${BACKUP_TIME.format(clock.instant())}-${UUID.randomUUID()}.bak"
            )
        try {
            // The fingerprint above is rechecked from the same bounded no-follow byte source.
            // Java NIO cannot make this cross-process check-and-move fully TOCTOU-free on every FS.
            when (files.atomicBackupIfUnchanged(scene, backup, expectedInvalid, maxBytes)) {
                ConditionalMoveResult.Moved -> Unit
                ConditionalMoveResult.Conflict -> return RecoveryCreateResult.Conflict
                ConditionalMoveResult.Unsupported ->
                    return RecoveryCreateResult.AtomicMoveUnsupported
                ConditionalMoveResult.Rejected -> return RecoveryCreateResult.Rejected
            }
        } catch (_: Exception) {
            return RecoveryCreateResult.Rejected
        }
        return when (val saved = saveCanonical(SceneFingerprint.Absent, canonicalBytes)) {
            is SceneSaveResult.Saved -> RecoveryCreateResult.Created(backup, saved.fingerprint)
            else -> RecoveryCreateResult.BackupRetained(backup, saved)
        }
    }

    private fun pathRejection(): PathRejection? =
        when {
            Files.isSymbolicLink(root) || !Files.isDirectory(root, NOFOLLOW_LINKS) ->
                PathRejection.ROOT_NOT_DIRECTORY
            !scene.startsWith(root) -> PathRejection.SCENE_ESCAPES_ROOT
            else -> null
        }

    private fun currentFingerprint(): SceneFingerprint? {
        return try {
            SceneFingerprint.of(files.readRegularNoFollowBounded(scene, maxBytes))
        } catch (_: java.nio.file.NoSuchFileException) {
            SceneFingerprint.Absent
        } catch (_: FileTooLargeException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC)
    }
}

sealed interface RecoveryCreateResult {
    data class Created(val backup: Path, val fingerprint: SceneFingerprint.Present) :
        RecoveryCreateResult

    data class BackupRetained(val backup: Path, val saveFailure: SceneSaveResult) :
        RecoveryCreateResult

    data object Conflict : RecoveryCreateResult

    data object AtomicMoveUnsupported : RecoveryCreateResult

    data object EncodingFailure : RecoveryCreateResult

    data object IntrinsicInvalid : RecoveryCreateResult

    data object Rejected : RecoveryCreateResult
}
