package gg.grounds.scene.editor.repository

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import gg.grounds.scene.format.SceneDocument
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.io.path.writeBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldSceneRepositoryTest {
    private val binding =
        SceneCatalogBinding(
            AssetCatalog(
                CatalogId("grounds:assets"),
                "1",
                CatalogVersionRange(CatalogId("grounds:resourcepacks"), "1", "1"),
                emptyMap(),
            ),
            ActionCatalog(CatalogId("grounds:actions"), "1", emptyMap()),
        )

    @Test
    fun `load distinguishes absence valid canonical scene and preserves invalid source bytes`() {
        val root = Files.createTempDirectory("scene-root")
        val repository = WorldSceneRepository(root)
        assertTrue(repository.load() is SceneLoadResult.Absent)
        val bytes = canonical(binding.newDocument("grounds:test"))
        root.resolve("scene.json").writeBytes(bytes)
        assertTrue(repository.load() is SceneLoadResult.Loaded)
        val invalid = "not json".encodeToByteArray()
        root.resolve("scene.json").writeBytes(invalid)
        val result = repository.load() as SceneLoadResult.Invalid
        assertTrue(result.bytes.contentEquals(invalid))
        val exposed = result.bytes
        exposed[0] = 'X'.code.toByte()
        assertTrue(result.bytes.contentEquals(invalid))

        root.resolve("scene.json").writeBytes(bytes)
        val loaded = repository.load() as SceneLoadResult.Loaded
        val canonicalCopy = loaded.canonicalBytes
        canonicalCopy[0] = 'X'.code.toByte()
        assertTrue(loaded.canonicalBytes.contentEquals(bytes))
    }

    @Test
    fun `fingerprints use exact bytes and absence is not an empty file`() {
        assertFalse(SceneFingerprint.Absent == SceneFingerprint.of(byteArrayOf()))
        assertFalse(
            SceneFingerprint.of("{}".encodeToByteArray()) ==
                SceneFingerprint.of("{ }".encodeToByteArray())
        )
    }

    @Test
    fun `load rejects oversized regular files and scene or root symlinks`() {
        val root = Files.createTempDirectory("scene-root")
        val repository = WorldSceneRepository(root, maxBytes = 8)
        root.resolve("scene.json").writeBytes(ByteArray(9))
        assertEquals(
            PathRejection.FILE_TOO_LARGE,
            (repository.load() as SceneLoadResult.Rejected).reason,
        )
        val target = Files.createTempFile("scene-target", ".json")
        Files.deleteIfExists(root.resolve("scene.json"))
        Files.createSymbolicLink(root.resolve("scene.json"), target)
        assertEquals(
            PathRejection.SCENE_SYMLINK,
            (repository.load() as SceneLoadResult.Rejected).reason,
        )
        val linkedRoot = Files.createTempDirectory("scene-linked-root")
        val rootLink = root.resolveSibling("scene-root-link-${UUID.randomUUID()}")
        Files.createSymbolicLink(rootLink, linkedRoot)
        assertEquals(
            PathRejection.ROOT_NOT_DIRECTORY,
            (WorldSceneRepository(rootLink).load() as SceneLoadResult.Rejected).reason,
        )
    }

    @Test
    fun `service save creates an absent canonical root and loaded fingerprint detects disk conflicts`() {
        val root = Files.createTempDirectory("scene-root")
        val repository = WorldSceneRepository(root)
        val document = binding.newDocument("grounds:test")
        val bytes = canonical(document)
        val id = UUID.randomUUID()
        val sessions = EditorSessionService(binding)
        sessions.open(id, document)
        assertTrue(sessions.save(id, repository) is SceneSaveResult.Saved)
        assertTrue(Files.readAllBytes(root.resolve("scene.json")).contentEquals(bytes))
        assertFalse(sessions.hasUnsavedChanges(id))
        val loaded = repository.load() as SceneLoadResult.Loaded
        root.resolve("scene.json").writeBytes("changed".encodeToByteArray())
        val reloaded = EditorSessionService(binding)
        reloaded.open(id, document, loaded.fingerprint)
        assertTrue(reloaded.save(id, repository) is SceneSaveResult.FingerprintConflict)
    }

    @Test
    fun `forged JVM-visible reservation is stale and cannot replace a scene`() {
        val root = Files.createTempDirectory("scene-forged-reservation")
        val repository = WorldSceneRepository(root)
        val document = binding.newDocument("grounds:forge")
        val original = "external".encodeToByteArray()
        root.resolve("scene.json").writeBytes(original)
        val service = EditorSessionService(binding)
        val world = UUID.randomUUID()
        service.open(world, document)
        val bytes = canonical(document)
        val constructor =
            EditorSessionService.SaveReservation::class.java.declaredConstructors.single()
        val forged =
            constructor.newInstance(
                service,
                UUID.randomUUID(),
                world,
                0L,
                document,
                bytes,
                SceneFingerprint.of(original),
            ) as EditorSessionService.SaveReservation

        assertTrue(repository.save(forged) is SceneSaveResult.StaleGeneration)
        assertTrue(Files.readAllBytes(root.resolve("scene.json")).contentEquals(original))
        assertFalse(
            EditorSessionService::class.java.declaredMethods.any {
                it.name in setOf("beginSave", "finishSave") &&
                    java.lang.reflect.Modifier.isPublic(it.modifiers)
            }
        )
    }

    @Test
    fun `conditional move conflict preserves concurrent external bytes and unsupported retains old scene`() {
        val root = Files.createTempDirectory("scene-cas")
        val original = canonical(binding.newDocument("grounds:old"))
        root.resolve("scene.json").writeBytes(original)
        val expected = SceneFingerprint.of(original)
        val document = binding.newDocument("grounds:new")
        val service = EditorSessionService(binding)
        val world = UUID.randomUUID()
        service.open(world, document, expected)
        val external = "external-writer".encodeToByteArray()
        val conflictRepository = WorldSceneRepository(root, ExternalChangeStore(external))
        assertTrue(service.save(world, conflictRepository) is SceneSaveResult.FingerprintConflict)
        assertTrue(Files.readAllBytes(root.resolve("scene.json")).contentEquals(external))

        val unsupportedService = EditorSessionService(binding)
        unsupportedService.open(world, document, SceneFingerprint.of(external))
        val unsupportedRepository = WorldSceneRepository(root, UnsupportedMoveStore())
        assertTrue(
            unsupportedService.save(world, unsupportedRepository)
                is SceneSaveResult.AtomicMoveUnsupported
        )
        assertTrue(Files.readAllBytes(root.resolve("scene.json")).contentEquals(external))
    }

    @Test
    fun `cleanup failure cannot mask conflict unsupported or io save outcomes`() {
        listOf(
                ConditionalMoveResult.Conflict to SceneSaveResult.FingerprintConflict,
                ConditionalMoveResult.Unsupported to SceneSaveResult.AtomicMoveUnsupported,
                ConditionalMoveResult.Rejected to SceneSaveResult.IoFailure("move"),
            )
            .forEach { (move, expected) ->
                val root = Files.createTempDirectory("scene-cleanup")
                val original = canonical(binding.newDocument("grounds:old"))
                root.resolve("scene.json").writeBytes(original)
                val world = UUID.randomUUID()
                val service = EditorSessionService(binding)
                service.open(
                    world,
                    binding.newDocument("grounds:new"),
                    SceneFingerprint.of(original),
                )

                val actual =
                    service.save(world, WorldSceneRepository(root, CleanupThrowingStore(move)))

                assertEquals(expected::class, actual::class)
                assertTrue(Files.readAllBytes(root.resolve("scene.json")).contentEquals(original))
            }
    }

    @Test
    fun `serialized concurrent saves allow exactly one same-fingerprint replacement`() {
        val root = Files.createTempDirectory("scene-concurrent")
        val original = canonical(binding.newDocument("grounds:old"))
        root.resolve("scene.json").writeBytes(original)
        val repository = WorldSceneRepository(root)
        val expected = SceneFingerprint.of(original)
        val first =
            EditorSessionService(binding).also {
                it.open(UUID(11, 1), binding.newDocument("grounds:first"), expected)
            }
        val second =
            EditorSessionService(binding).also {
                it.open(UUID(11, 2), binding.newDocument("grounds:second"), expected)
            }
        val executor = Executors.newFixedThreadPool(2)
        val results =
            executor
                .invokeAll(
                    listOf(
                        Callable { first.save(UUID(11, 1), repository) },
                        Callable { second.save(UUID(11, 2), repository) },
                    )
                )
                .map { it.get() }
        executor.shutdown()
        assertEquals(1, results.count { it is SceneSaveResult.Saved })
        assertEquals(1, results.count { it is SceneSaveResult.FingerprintConflict })
    }

    @Test
    fun `recovery only backs up matching invalid source before atomically creating a document`() {
        val root = Files.createTempDirectory("scene-root")
        val repository = WorldSceneRepository(root)
        val invalid = "not json".encodeToByteArray()
        root.resolve("scene.json").writeBytes(invalid)
        val fingerprint = SceneFingerprint.of(invalid) as SceneFingerprint.Present
        val document = binding.newDocument("grounds:recovered")

        val recovered = repository.backupInvalidAndCreate(fingerprint, document)

        assertTrue(recovered is RecoveryCreateResult.Created)
        val backup = (recovered as RecoveryCreateResult.Created).backup
        assertTrue(backup.parent == root)
        assertTrue(
            backup.fileName
                .toString()
                .matches(Regex("scene\\.json\\.invalid-\\d{17}-[0-9a-f-]{36}\\.bak"))
        )
        assertTrue(Files.readAllBytes(backup).contentEquals(invalid))
        assertTrue(repository.load() is SceneLoadResult.Loaded)
    }

    @Test
    fun `recovery refuses changed or valid source without creating a backup`() {
        val root = Files.createTempDirectory("scene-root")
        val repository = WorldSceneRepository(root)
        val original = "not json".encodeToByteArray()
        root.resolve("scene.json").writeBytes(original)
        val expected = SceneFingerprint.of(original) as SceneFingerprint.Present
        root.resolve("scene.json").writeBytes("changed".encodeToByteArray())
        assertTrue(
            repository.backupInvalidAndCreate(expected, binding.newDocument("grounds:recovered"))
                is RecoveryCreateResult.Conflict
        )
        root.resolve("scene.json").writeBytes(canonical(binding.newDocument("grounds:valid")))
        val validFingerprint =
            SceneFingerprint.of(Files.readAllBytes(root.resolve("scene.json")))
                as SceneFingerprint.Present
        assertTrue(
            repository.backupInvalidAndCreate(
                validFingerprint,
                binding.newDocument("grounds:recovered"),
            ) is RecoveryCreateResult.Conflict
        )
        assertEquals(1, Files.list(root).use { it.count() })
    }

    @Test
    fun `intrinsically invalid recovery leaves invalid source byte-for-byte in place`() {
        val root = Files.createTempDirectory("scene-root")
        val repository = WorldSceneRepository(root)
        val invalid = "not json".encodeToByteArray()
        root.resolve("scene.json").writeBytes(invalid)
        val validDocument = binding.newDocument("grounds:recovered")
        val invalidDocument =
            SceneDocument(
                0,
                validDocument.id,
                validDocument.metadata,
                validDocument.catalogs,
                validDocument.groups,
                validDocument.elements,
            )

        val result =
            repository.backupInvalidAndCreate(
                SceneFingerprint.of(invalid) as SceneFingerprint.Present,
                invalidDocument,
            )

        assertTrue(result is RecoveryCreateResult.IntrinsicInvalid)
        assertTrue(Files.readAllBytes(root.resolve("scene.json")).contentEquals(invalid))
        assertEquals(1, Files.list(root).use { it.count() })
    }

    @Test
    fun `recovery retains the backup when creation cannot atomically replace the new scene`() {
        val root = Files.createTempDirectory("scene-root")
        val invalid = "not json".encodeToByteArray()
        root.resolve("scene.json").writeBytes(invalid)
        val store = FailSecondAtomicMoveStore()
        val repository = WorldSceneRepository(root, store)

        val result =
            repository.backupInvalidAndCreate(
                SceneFingerprint.of(invalid) as SceneFingerprint.Present,
                binding.newDocument("grounds:recovered"),
            )

        assertTrue(result is RecoveryCreateResult.BackupRetained)
        val retained = result as RecoveryCreateResult.BackupRetained
        assertTrue(retained.saveFailure is SceneSaveResult.AtomicMoveUnsupported)
        assertTrue(Files.readAllBytes(retained.backup).contentEquals(invalid))
        assertFalse(Files.exists(root.resolve("scene.json")))
        assertEquals(2, store.atomicMoves)
    }

    private fun canonical(document: gg.grounds.scene.format.SceneDocument): ByteArray =
        (gg.grounds.scene.format.SceneJson.encode(document)
                as gg.grounds.scene.format.SceneEncodeResult.Success)
            .bytes

    private class FailSecondAtomicMoveStore : AtomicSceneFileStore {
        private val delegate = NioAtomicSceneFileStore
        var atomicMoves = 0

        override fun readRegularNoFollowBounded(path: Path, maxBytes: Int): ByteArray =
            delegate.readRegularNoFollowBounded(path, maxBytes)

        override fun createTempSibling(target: Path): Path = delegate.createTempSibling(target)

        override fun writeAndFlush(path: Path, bytes: ByteArray) =
            delegate.writeAndFlush(path, bytes)

        override fun atomicMoveIfUnchanged(
            source: Path,
            target: Path,
            expected: SceneFingerprint,
            maxBytes: Int,
        ): ConditionalMoveResult {
            atomicMoves += 1
            if (atomicMoves == 2) return ConditionalMoveResult.Unsupported
            return delegate.atomicMoveIfUnchanged(source, target, expected, maxBytes)
        }

        override fun atomicBackupIfUnchanged(
            source: Path,
            target: Path,
            expectedSource: SceneFingerprint,
            maxBytes: Int,
        ): ConditionalMoveResult {
            atomicMoves += 1
            if (atomicMoves == 2) return ConditionalMoveResult.Unsupported
            return delegate.atomicBackupIfUnchanged(source, target, expectedSource, maxBytes)
        }

        override fun deleteIfExists(path: Path) = delegate.deleteIfExists(path)
    }

    private class ExternalChangeStore(private val replacement: ByteArray) :
        AtomicSceneFileStore by NioAtomicSceneFileStore {
        override fun atomicMoveIfUnchanged(
            source: Path,
            target: Path,
            expected: SceneFingerprint,
            maxBytes: Int,
        ): ConditionalMoveResult {
            Files.write(target, replacement, StandardOpenOption.TRUNCATE_EXISTING)
            return NioAtomicSceneFileStore.atomicMoveIfUnchanged(source, target, expected, maxBytes)
        }
    }

    private class UnsupportedMoveStore : AtomicSceneFileStore by NioAtomicSceneFileStore {
        override fun atomicMoveIfUnchanged(
            source: Path,
            target: Path,
            expected: SceneFingerprint,
            maxBytes: Int,
        ): ConditionalMoveResult = ConditionalMoveResult.Unsupported
    }

    private class CleanupThrowingStore(private val move: ConditionalMoveResult) :
        AtomicSceneFileStore by NioAtomicSceneFileStore {
        override fun atomicMoveIfUnchanged(
            source: Path,
            target: Path,
            expected: SceneFingerprint,
            maxBytes: Int,
        ): ConditionalMoveResult = move

        override fun deleteIfExists(path: Path) {
            throw IllegalStateException("cleanup")
        }
    }
}
