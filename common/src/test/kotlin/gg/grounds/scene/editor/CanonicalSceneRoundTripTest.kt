package gg.grounds.scene.editor

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.editor.repository.SceneLoadResult
import gg.grounds.scene.editor.repository.SceneSaveResult
import gg.grounds.scene.editor.repository.WorldSceneRepository
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneDecodeResult
import gg.grounds.scene.format.SceneEncodeResult
import gg.grounds.scene.format.SceneJson
import gg.grounds.scene.format.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CanonicalSceneRoundTripTest {
    @TempDir lateinit var root: Path

    @Test
    fun `mutated bootstrap scene saves decodes and reloads canonically`() {
        val catalogs = SceneCatalogBinding.production()
        val worldId = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val service = EditorSessionService(catalogs)
        service.open(worldId, catalogs.newDocument("grounds:round-trip"))
        assertTrue(
            service
                .mutate(
                    worldId,
                    SceneMutations.createProp(
                        actor,
                        LocalId("marker"),
                        AssetKey("grounds:editor/marker"),
                        PlayerPlacement(Vec3(1.0, 2.0, 3.0), 45.0),
                    ),
                )
                .accepted
        )
        assertTrue(
            service
                .mutate(
                    worldId,
                    SceneMutations.createNpc(
                        actor,
                        LocalId("guide"),
                        AssetKey("grounds:editor/guide"),
                        PlayerPlacement(Vec3(4.0, 5.0, 6.0), 90.0),
                    ),
                )
                .accepted
        )

        val repository = WorldSceneRepository(root)
        assertTrue(service.save(worldId, repository) is SceneSaveResult.Saved)
        val bytes = Files.readAllBytes(root.resolve("scene.json"))
        val decoded = SceneJson.decode(bytes)
        assertTrue(decoded is SceneDecodeResult.Success)
        assertEquals(
            bytes.toList(),
            (SceneJson.encode((decoded as SceneDecodeResult.Success).scene)
                    as SceneEncodeResult.Success)
                .bytes
                .toList(),
        )

        val reloaded = EditorSessionService(catalogs)
        val load = repository.load()
        assertTrue(load is SceneLoadResult.Loaded)
        reloaded.openFromLoad(worldId, load)
        val canonical =
            SceneJson.encode(requireNotNull(reloaded.session(worldId)).document)
                as SceneEncodeResult.Success
        assertEquals(bytes.toList(), canonical.bytes.toList())
    }
}
