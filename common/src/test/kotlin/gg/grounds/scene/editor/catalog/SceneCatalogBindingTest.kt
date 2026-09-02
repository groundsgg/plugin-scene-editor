package gg.grounds.scene.editor.catalog

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.scene.format.ActionKey
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogReference
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.SceneCatalogReferences
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.TriggerBinding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneCatalogBindingTest {
    @Test
    fun `production binding resolves legacy and current pins without repinning`() {
        val binding = SceneCatalogBinding.production()
        val fresh = binding.newDocument("grounds:test-scene")
        val legacy =
            document(
                fresh,
                catalogs =
                    SceneCatalogReferences(
                        fresh.catalogs.assets,
                        CatalogReference(CatalogId("grounds:actions"), "1"),
                    ),
            )
        val unknownRevision =
            document(
                fresh,
                catalogs =
                    SceneCatalogReferences(
                        fresh.catalogs.assets,
                        CatalogReference(CatalogId("grounds:actions"), "999"),
                    ),
            )

        assertTrue(binding.status(legacy).isVerified)
        assertEquals("1", legacy.catalogs.actions.version)
        assertEquals("2", fresh.catalogs.actions.version)
        assertTrue(binding.actionsVerified(legacy))
        assertTrue(binding.status(fresh).isVerified)
        assertFalse(binding.status(unknownRevision).isVerified)
    }

    @Test
    fun `current navigator binding stays verified through a transform and unknown actions stay unverified`() {
        val binding = SceneCatalogBinding.production()
        val navigator = ApplicationAction(LobbySceneCatalogs.OPEN_NAVIGATOR, emptyMap())
        val unknown = ApplicationAction(ActionKey("grounds:lobby/unknown"), emptyMap())
        val unexpectedArgument =
            ApplicationAction(
                LobbySceneCatalogs.OPEN_NAVIGATOR,
                mapOf(LocalId("unexpected") to gg.grounds.scene.format.StringArgument("value")),
            )
        val document = binding.newDocument("grounds:navigator", elements = listOf(npc(navigator)))

        assertTrue(binding.actionsVerified(document))
        assertTrue(binding.actionVerified(document, navigator))
        assertFalse(binding.actionsVerified(document(document, elements = listOf(npc(unknown)))))
        assertFalse(
            binding.actionsVerified(document(document, elements = listOf(npc(unexpectedArgument))))
        )
    }

    private fun document(
        source: SceneDocument,
        catalogs: SceneCatalogReferences = source.catalogs,
        elements: List<gg.grounds.scene.format.SceneElement> = source.elements,
    ) =
        SceneDocument(
            source.schemaVersion,
            source.id,
            source.metadata,
            catalogs,
            source.groups,
            elements,
        )

    private fun npc(action: ApplicationAction): Npc =
        Npc(
            LocalId("guide"),
            null,
            gg.grounds.scene.format.Transform(
                gg.grounds.scene.format.Vec3(0.0, 0.0, 0.0),
                gg.grounds.scene.format.EulerRotation(0.0, 0.0, 0.0),
                gg.grounds.scene.format.Vec3(1.0, 1.0, 1.0),
            ),
            body = gg.grounds.scene.format.AssetKey("grounds:editor/guide"),
            label = null,
            labelOffset = gg.grounds.scene.format.Vec3(0.0, 2.25, 0.0),
            look = gg.grounds.scene.format.LookBehavior.Fixed,
            initialAnimation = null,
            interactionBounds =
                gg.grounds.scene.format.LocalBounds(
                    gg.grounds.scene.format.Vec3(-0.3, 0.0, -0.3),
                    gg.grounds.scene.format.Vec3(0.3, 1.8, 0.3),
                ),
            proximity = null,
            bindings =
                listOf(TriggerBinding(SceneTrigger.LEFT_CLICK, emptyList(), 0, 0, listOf(action))),
        )
}
