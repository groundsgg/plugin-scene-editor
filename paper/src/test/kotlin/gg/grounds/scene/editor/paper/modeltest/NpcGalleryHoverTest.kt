package gg.grounds.scene.editor.paper.modeltest

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.format.*
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NpcGalleryHoverTest {
    private val catalogs = SceneCatalogBinding.production()
    private val player = UUID(1, 1)
    private val other = UUID(2, 2)

    private fun npc(id: String, z: Double, visible: Boolean = true) =
        Npc(
            LocalId(id),
            null,
            Transform(Vec3(0.0, 0.0, z), EulerRotation(25.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            visible = visible,
            body = AssetKey("grounds:editor/guide"),
            label = null,
            labelOffset = Vec3(0.0, 2.0, 0.0),
            look = LookBehavior.Fixed,
            initialAnimation = null,
            interactionBounds = LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
            proximity = null,
            bindings = emptyList(),
        )

    @Test
    fun `gallery hover shares nearest selection and keeps viewer states independent`() {
        val hover = NpcGalleryHover()
        val document =
            catalogs.newDocument(
                "grounds:hover-test",
                elements = listOf(npc("far", 4.0), npc("near", 2.0)),
            )
        val eye = Vec3(0.0, 1.0, 0.0)
        assertEquals(LocalId("near"), hover.update(player, document, eye, Vec3(0.0, 0.0, 1.0)))
        assertNull(hover.update(other, document, eye, Vec3(1.0, 0.0, 0.0)))
        assertEquals(LocalId("near"), hover.current(player))
        assertNull(hover.update(player, document, eye, Vec3(0.0, 0.0, -1.0)))
        assertNull(hover.current(player))
    }

    @Test
    fun `hidden and out of reach NPCs cannot be hovered and cleanup forgets the viewer`() {
        val hover = NpcGalleryHover()
        val eye = Vec3(0.0, 1.0, 0.0)
        val hidden =
            catalogs.newDocument(
                "grounds:hover-test",
                elements = listOf(npc("hidden", 2.0, false), npc("too-far", 8.0)),
            )
        assertNull(hover.update(player, hidden, eye, Vec3(0.0, 0.0, 1.0)))
        val visible =
            catalogs.newDocument("grounds:hover-test", elements = listOf(npc("near", 2.0)))
        assertEquals(LocalId("near"), hover.update(player, visible, eye, Vec3(0.0, 0.0, 1.0)))
        hover.removeViewer(player)
        assertNull(hover.current(player))
    }
}
