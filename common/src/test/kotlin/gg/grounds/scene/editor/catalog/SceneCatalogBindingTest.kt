package gg.grounds.scene.editor.catalog

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.ActionDefinition
import gg.grounds.scene.format.ActionKey
import gg.grounds.scene.format.ActionParameter
import gg.grounds.scene.format.ActionParameterType
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogReference
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.NoConstraints
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.SceneCatalogReferences
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.TriggerBinding
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneCatalogBindingTest {
    @Test
    fun `queue NPC actions validate required modes and old revision two documents retain their pin`() {
        val binding = SceneCatalogBinding.production()
        fun queue(mode: String) =
            ApplicationAction(
                ActionKey("grounds:lobby/toggle_queue"),
                mapOf(
                    gg.grounds.scene.format.LocalId("mode") to
                        gg.grounds.scene.format.StringArgument(mode)
                ),
            )
        val scene =
            binding.newDocument("grounds:queue", elements = listOf(npc(queue("bedwars-2x1"))))
        assertTrue(binding.actionsVerified(scene))
        for (mode in listOf("", "queue leave", "../duel", "A", "a".repeat(129))) {
            assertFalse(
                binding.actionsVerified(document(scene, elements = listOf(npc(queue(mode)))))
            )
        }
        assertFalse(
            binding.actionsVerified(
                document(
                    scene,
                    elements =
                        listOf(
                            npc(
                                ApplicationAction(
                                    ActionKey("grounds:lobby/toggle_queue"),
                                    emptyMap(),
                                )
                            )
                        ),
                )
            )
        )
        val revisionTwo =
            document(
                scene,
                catalogs =
                    SceneCatalogReferences(
                        scene.catalogs.assets,
                        CatalogReference(CatalogId("grounds:actions"), "2"),
                    ),
                elements =
                    listOf(
                        npc(
                            ApplicationAction(ActionKey("grounds:lobby/open_navigator"), emptyMap())
                        )
                    ),
            )
        assertTrue(binding.actionsVerified(revisionTwo))
        assertEquals("2", revisionTwo.catalogs.actions.version)
        assertFalse(binding.actionsVerified(document(scene, catalogs = revisionTwo.catalogs)))
    }

    @Test
    fun `production binding creates and verifies stable asset catalog documents`() {
        val binding = SceneCatalogBinding.production()
        val fresh = binding.newDocument("grounds:fresh")
        val stableReference = CatalogReference(CatalogId("grounds:assets"), binding.assets.version)
        val stableDocument =
            SceneDocument(
                1,
                gg.grounds.scene.format.SceneId("grounds:stable"),
                gg.grounds.scene.format.SceneMetadata("grounds:stable", null, emptySet()),
                SceneCatalogReferences(stableReference, fresh.catalogs.actions),
                emptyList(),
                emptyList(),
            )

        assertAll(
            { assertEquals(stableReference, fresh.catalogs.assets) },
            { assertTrue(binding.status(stableDocument).isVerified) },
        )
    }

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
        assertEquals("3", fresh.catalogs.actions.version)
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

    @Test
    fun `parameterless actions exclude catalog entries that require authored arguments`() {
        val navigator = ActionKey("grounds:lobby/open_navigator")
        val parameterized = ActionKey("grounds:lobby/teleport")
        val binding =
            SceneCatalogBinding(
                GroundsAssetCatalog.catalog,
                ActionCatalog(
                    CatalogId("grounds:actions"),
                    "1",
                    mapOf(
                        navigator to ActionDefinition(navigator, "Navigator", "Open", emptyMap()),
                        parameterized to
                            ActionDefinition(
                                parameterized,
                                "Teleport",
                                "Teleport",
                                mapOf(
                                    LocalId("target") to
                                        ActionParameter(
                                            LocalId("target"),
                                            ActionParameterType.STRING,
                                            true,
                                            null,
                                            NoConstraints,
                                        )
                                ),
                            ),
                    ),
                ),
            )

        assertEquals(
            listOf(navigator),
            binding.parameterlessActionsFor(binding.newDocument("grounds:test")).map { it.key },
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
