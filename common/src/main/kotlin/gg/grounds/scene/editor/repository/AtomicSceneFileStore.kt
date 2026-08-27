package gg.grounds.scene.editor.repository

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap

/** Injectable atomic filesystem boundary. No caller is offered a non-atomic fallback. */
interface AtomicSceneFileStore {
    /** Reads at most [maxBytes]; implementations reject symlinks and non-regular files. */
    fun readRegularNoFollowBounded(path: Path, maxBytes: Int): ByteArray

    fun createTempSibling(target: Path): Path

    fun writeAndFlush(path: Path, bytes: ByteArray)

    fun atomicMoveIfUnchanged(
        source: Path,
        target: Path,
        expected: SceneFingerprint,
        maxBytes: Int,
    ): ConditionalMoveResult

    fun atomicBackupIfUnchanged(
        source: Path,
        target: Path,
        expectedSource: SceneFingerprint,
        maxBytes: Int,
    ): ConditionalMoveResult

    fun deleteIfExists(path: Path)
}

/**
 * Cleanup never changes the persistence result that caused it; diagnostics are deliberately
 * optional.
 */
internal fun bestEffortTempCleanup(
    files: AtomicSceneFileStore,
    path: Path,
    errorHandler: (Throwable) -> Unit = {},
) {
    try {
        files.deleteIfExists(path)
    } catch (error: Throwable) {
        try {
            errorHandler(error)
        } catch (_: Throwable) {}
    }
}

object NioAtomicSceneFileStore : AtomicSceneFileStore {
    override fun readRegularNoFollowBounded(path: Path, maxBytes: Int): ByteArray {
        require(maxBytes >= 0)
        if (Files.isSymbolicLink(path)) throw SymbolicLinkRejectedException()
        val attributes =
            Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
        require(attributes.isRegularFile) { "not a regular file" }
        if (attributes.size() > maxBytes) throw FileTooLargeException()
        val capacity = maxBytes.toLong() + 1L
        require(capacity <= Int.MAX_VALUE) { "maxBytes is too large" }
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteArray(capacity.toInt())
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {}
            if (buffer.position() > maxBytes) throw FileTooLargeException()
            return bytes.copyOf(buffer.position())
        }
    }

    override fun createTempSibling(target: Path): Path =
        Files.createTempFile(target.parent, ".scene-", ".tmp")

    override fun writeAndFlush(path: Path, bytes: ByteArray) {
        java.nio.channels.FileChannel.open(path, WRITE, TRUNCATE_EXISTING).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    override fun atomicMoveIfUnchanged(
        source: Path,
        target: Path,
        expected: SceneFingerprint,
        maxBytes: Int,
    ): ConditionalMoveResult {
        val lock = locks.computeIfAbsent(target.toAbsolutePath().normalize()) { Any() }
        synchronized(lock) {
            val current =
                try {
                    SceneFingerprint.of(readRegularNoFollowBounded(target, maxBytes))
                } catch (_: java.nio.file.NoSuchFileException) {
                    SceneFingerprint.Absent
                } catch (_: Exception) {
                    return ConditionalMoveResult.Rejected
                }
            if (current != expected) return ConditionalMoveResult.Conflict
            return try {
                Files.move(source, target, ATOMIC_MOVE)
                ConditionalMoveResult.Moved
            } catch (_: AtomicMoveNotSupportedException) {
                ConditionalMoveResult.Unsupported
            }
        }
    }

    override fun atomicBackupIfUnchanged(
        source: Path,
        target: Path,
        expectedSource: SceneFingerprint,
        maxBytes: Int,
    ): ConditionalMoveResult {
        val keys =
            listOf(source.toAbsolutePath().normalize(), target.toAbsolutePath().normalize())
                .sortedBy { it.toString() }
        val first = locks.computeIfAbsent(keys[0]) { Any() }
        val second = locks.computeIfAbsent(keys[1]) { Any() }
        synchronized(first) {
            synchronized(second) {
                if (Files.exists(target, NOFOLLOW_LINKS)) return ConditionalMoveResult.Conflict
                val current =
                    try {
                        SceneFingerprint.of(readRegularNoFollowBounded(source, maxBytes))
                    } catch (_: Exception) {
                        return ConditionalMoveResult.Rejected
                    }
                if (current != expectedSource) return ConditionalMoveResult.Conflict
                return try {
                    Files.move(source, target, ATOMIC_MOVE)
                    ConditionalMoveResult.Moved
                } catch (_: AtomicMoveNotSupportedException) {
                    ConditionalMoveResult.Unsupported
                }
            }
        }
    }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}

sealed interface ConditionalMoveResult {
    data object Moved : ConditionalMoveResult

    data object Conflict : ConditionalMoveResult

    data object Unsupported : ConditionalMoveResult

    data object Rejected : ConditionalMoveResult
}

class FileTooLargeException : IllegalStateException()

class SymbolicLinkRejectedException : IllegalStateException()

private val locks = ConcurrentHashMap<Path, Any>()
