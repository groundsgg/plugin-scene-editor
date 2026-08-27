package gg.grounds.scene.editor.recovery

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.repository.AtomicSceneFileStore
import gg.grounds.scene.editor.repository.ConditionalMoveResult
import gg.grounds.scene.editor.repository.NioAtomicSceneFileStore
import gg.grounds.scene.editor.repository.SceneFingerprint
import gg.grounds.scene.editor.repository.SceneLoadResult
import gg.grounds.scene.editor.repository.WorldSceneRepository
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecoveryServiceTest {
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
    fun `export uses scene json rather than accepting caller supplied canonical bytes`() {
        val world = Files.createTempDirectory("scene-world")
        val data = Files.createTempDirectory("scene-data")
        val service = RecoveryService(WorldSceneRepository(world), data)
        val document = binding.newDocument("grounds:export")

        val exported = service.export(document)

        assertTrue(exported is RecoveryExportResult.Exported)
        val result = exported as RecoveryExportResult.Exported
        val bytes = Files.readAllBytes(result.export.path)
        assertTrue(bytes.contentEquals(result.export.bytes))
        assertTrue(result.export.fingerprint == SceneFingerprint.of(bytes))
        val leaked = result.export.bytes
        leaked[0] = (leaked[0].toInt() xor 1).toByte()
        assertFalse(Files.readAllBytes(result.export.path).contentEquals(leaked))
    }

    @Test
    fun `export rejects symlinked data and diagnostics roots and never writes through them`() {
        val world = Files.createTempDirectory("scene-world")
        val realData = Files.createTempDirectory("scene-data-real")
        val dataLink = realData.resolveSibling("scene-data-link-${UUID.randomUUID()}")
        Files.createSymbolicLink(dataLink, realData)
        val document = binding.newDocument("grounds:export")
        assertTrue(
            RecoveryService(WorldSceneRepository(world), dataLink).export(document)
                is RecoveryExportResult.RejectedRoot
        )

        val data = Files.createTempDirectory("scene-data")
        val elsewhere = Files.createTempDirectory("scene-diagnostics-real")
        Files.createSymbolicLink(data.resolve("scene-diagnostics"), elsewhere)
        assertTrue(
            RecoveryService(WorldSceneRepository(world), data).export(document)
                is RecoveryExportResult.RejectedRoot
        )
        assertTrue(Files.list(elsewhere).use { it.noneMatch { true } })
    }

    @Test
    fun `export keeps its target absent when atomic replacement is unsupported`() {
        val world = Files.createTempDirectory("scene-world")
        val data = Files.createTempDirectory("scene-data")
        val result =
            RecoveryService(WorldSceneRepository(world), data, files = UnsupportedExportMoveStore())
                .export(binding.newDocument("grounds:export"))
        assertTrue(result is RecoveryExportResult.AtomicMoveUnsupported)
        val diagnostics = data.resolve("scene-diagnostics")
        assertTrue(
            Files.list(diagnostics).use {
                it.noneMatch { path -> path.fileName.toString().endsWith(".json") }
            }
        )
    }

    @Test
    fun `export cleanup failure cannot mask unsupported or io outcomes`() {
        listOf(
                ConditionalMoveResult.Unsupported to
                    RecoveryExportResult.AtomicMoveUnsupported::class,
                ConditionalMoveResult.Rejected to RecoveryExportResult.IoFailure::class,
            )
            .forEach { (move, expected) ->
                val world = Files.createTempDirectory("scene-world")
                val data = Files.createTempDirectory("scene-data")
                val result =
                    RecoveryService(
                            WorldSceneRepository(world),
                            data,
                            files = CleanupThrowingExportStore(move),
                        )
                        .export(binding.newDocument("grounds:export"))
                assertEquals(expected, result::class)
            }
    }

    @Test
    fun `reload refuses dirty work and literal confirmation records discarded scene audit`() {
        val world = Files.createTempDirectory("scene-world")
        val data = Files.createTempDirectory("scene-data")
        val repository = WorldSceneRepository(world)
        val service = RecoveryService(repository, data)
        val document = binding.newDocument("grounds:discard")
        world
            .resolve("scene.json")
            .toFile()
            .writeBytes(
                (gg.grounds.scene.format.SceneJson.encode(document)
                        as gg.grounds.scene.format.SceneEncodeResult.Success)
                    .bytes
            )
        val fingerprint = SceneFingerprint.of(Files.readAllBytes(world.resolve("scene.json")))

        assertTrue(service.reload(dirty = true) is RecoveryReloadResult.DirtyRefused)
        assertTrue(service.reload(dirty = false) is RecoveryReloadResult.Loaded)
        assertTrue(
            service.discardAndReload("CONFIRM", "grounds:discard", fingerprint)
                is RecoveryReloadResult.ConfirmationRequired
        )
        val discarded = service.discardAndReload("confirm", "grounds:discard", fingerprint)

        assertTrue(discarded is RecoveryReloadResult.Discarded)
        discarded as RecoveryReloadResult.Discarded
        assertTrue(discarded.load is SceneLoadResult.Loaded)
        assertTrue(discarded.audit.discardedSceneId == "grounds:discard")
        assertTrue(discarded.audit.discardedFingerprint == fingerprint)
    }

    private class UnsupportedExportMoveStore : AtomicSceneFileStore by NioAtomicSceneFileStore {
        override fun atomicMoveIfUnchanged(
            source: Path,
            target: Path,
            expected: SceneFingerprint,
            maxBytes: Int,
        ): ConditionalMoveResult = ConditionalMoveResult.Unsupported
    }

    private class CleanupThrowingExportStore(private val move: ConditionalMoveResult) :
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
