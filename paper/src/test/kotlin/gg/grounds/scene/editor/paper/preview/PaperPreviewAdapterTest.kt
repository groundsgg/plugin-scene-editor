package gg.grounds.scene.editor.paper.preview

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutationResult
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.editor.session.EditorSelection
import gg.grounds.scene.editor.session.SessionPreviewSnapshot
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import java.util.UUID
import net.kyori.adventure.text.Component
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PaperPreviewAdapterTest {
    private val worldId = UUID(1, 2)
    private val viewerId = UUID(3, 4)
    private val actor = UUID(5, 6)
    private val catalogs = SceneCatalogBinding.production()

    @Test
    fun `maps prop and npc placeholders labels and viewer selection from immutable snapshot`() {
        var document = catalogs.newDocument("grounds:preview")
        document =
            (SceneMutations.createProp(
                        actor,
                        LocalId("marker"),
                        AssetKey("grounds:editor/marker"),
                        PlayerPlacement(Vec3(1.0, 2.0, 3.0), 0.0),
                    )
                    .apply(document, catalogs) as SceneMutationResult.Success)
                .document
        document =
            (SceneMutations.createNpc(
                        actor,
                        LocalId("guide"),
                        AssetKey("grounds:editor/guide"),
                        PlayerPlacement(Vec3(4.0, 5.0, 6.0), 0.0),
                    )
                    .apply(document, catalogs) as SceneMutationResult.Success)
                .document
        document =
            (SceneMutations.setLabel(actor, LocalId("guide"), Component.text("Guide"))
                    .apply(document, catalogs) as SceneMutationResult.Success)
                .document
        val captured = mutableListOf<PreviewDescriptor>()
        val entity = mock(Entity::class.java)
        val adapter =
            PaperPreviewAdapter(
                PreviewEntityFactory { _, descriptor ->
                    captured += descriptor
                    PreviewHandle(listOf(entity))
                }
            )
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(worldId)
        val viewer = mock(Player::class.java)
        `when`(viewer.world).thenReturn(world)
        `when`(viewer.uniqueId).thenReturn(viewerId)

        adapter.reconcile(
            viewer,
            SessionPreviewSnapshot(
                worldId,
                document,
                1,
                mapOf(viewerId to EditorSelection(LocalId("guide"))),
            ),
        )

        assertEquals(listOf(PreviewKind.PROP, PreviewKind.NPC), captured.map { it.kind })
        assertEquals(Component.text("Guide"), captured.single { it.kind == PreviewKind.NPC }.label)
        assertTrue(captured.single { it.elementId == "guide" }.selected)
        assertTrue(!captured.single { it.elementId == "marker" }.selected)

        adapter.clearWorld(worldId)
        verify(entity, org.mockito.Mockito.times(2)).remove()
    }

    @Test
    fun `older async snapshot cannot replace a newer preview generation`() {
        val document = catalogs.newDocument("grounds:preview")
        var creates = 0
        val adapter =
            PaperPreviewAdapter(
                PreviewEntityFactory { _, _ ->
                    creates++
                    PreviewHandle(emptyList())
                }
            )
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(worldId)
        val viewer = mock(Player::class.java)
        `when`(viewer.world).thenReturn(world)
        `when`(viewer.uniqueId).thenReturn(viewerId)

        adapter.reconcile(viewer, SessionPreviewSnapshot(worldId, document, 4, emptyMap()))
        val stale =
            adapter.reconcile(viewer, SessionPreviewSnapshot(worldId, document, 3, emptyMap()))

        assertEquals(ReconcileResult.STALE, stale)
        assertEquals(0, creates)
    }
}
