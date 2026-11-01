package gg.grounds.scene.editor.paper.modeltest

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.format.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class NpcPreviewGalleryTest {
    private val catalogs = SceneCatalogBinding.production()
    private val origin =
        Transform(Vec3(10.0, 140.0, 20.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))

    @Test
    fun `queue gallery uses the chosen five bodies and source greeting clips without application actions`() {
        assumeTrue(AssetKey("grounds:npc/townfolk/builder") in catalogs.assets.assets)
        val scene = npcPreviewGallery(catalogs, origin, false, GalleryPlayback.IDLE)
        val npcs = scene.elements.filterIsInstance<Npc>()
        assertEquals(5, npcs.size)
        assertEquals(
            setOf(
                "guildfolk/forge_master",
                "craftmasters/shipman",
                "townfolk/builder",
                "townfolk/ragpicker",
                "guildfolk/crystal_prospector",
            ),
            npcs.map { it.body.value.removePrefix("grounds:npc/") }.toSet(),
        )
        assertTrue(
            npcs.all {
                it.initialAnimation == LocalId("idle") && it.proximity == ProximitySensor(4.0, 5.0)
            }
        )
        assertTrue(npcs.all { it.transform.position.y == 140.0 && it.transform.position.z == 26.0 })
        assertTrue(
            npcs.flatMap { it.bindings }.flatMap { it.actions }.none { it is ApplicationAction }
        )
        assertTrue(catalogs.status(scene).validation.isValid)
    }

    @Test
    fun `all gallery selects valid original clips for every model and retains its anchor`() {
        assumeTrue(AssetKey("grounds:npc/townfolk/builder") in catalogs.assets.assets)
        for (playback in GalleryPlayback.entries) {
            val scene = npcPreviewGallery(catalogs, origin, true, playback)
            assertEquals(15, scene.elements.size)
            assertEquals(15, scene.elements.map { it.id }.distinct().size)
            for (npc in scene.elements.filterIsInstance<Npc>()) {
                val clips = catalogs.assets.assets.getValue(npc.body).animations
                assertTrue(npc.initialAnimation in clips)
                assertEquals(140.0, npc.transform.position.y)
                assertEquals(
                    if (playback == GalleryPlayback.GREETING)
                        (if (LocalId("hello") in clips) "hello" else "wave")
                    else playback.name.lowercase(),
                    npc.initialAnimation!!.value,
                )
            }
            assertTrue(catalogs.status(scene).validation.isValid)
        }
    }
}
