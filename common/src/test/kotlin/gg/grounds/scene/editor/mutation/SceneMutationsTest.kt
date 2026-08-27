package gg.grounds.scene.editor.mutation

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.ActionKey
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetDefinition
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogReference
import gg.grounds.scene.format.CatalogVersionRange
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.LookBehavior
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.TriggerBinding
import gg.grounds.scene.format.Vec3
import java.util.UUID
import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneMutationsTest {
    private val binding =
        SceneCatalogBinding(
            testAssets,
            ActionCatalog(CatalogId("grounds:actions"), "1", emptyMap()),
        )
    private val actor = UUID(1, 2)

    @Test
    fun `new documents pin exact catalogs and create props with editor defaults`() {
        val document = binding.newDocument("grounds:test")

        val result =
            SceneMutations.createProp(
                    actor,
                    LocalId("marker"),
                    AssetKey("grounds:editor/marker"),
                    PlayerPlacement(Vec3(1.0, 2.0, 3.0), 270.0),
                )
                .apply(document, binding)

        val prop = assertSuccess(result).elements.single() as Prop
        assertEquals(CatalogReference(CatalogId("grounds:assets"), "6"), document.catalogs.assets)
        assertEquals(CatalogReference(CatalogId("grounds:actions"), "1"), document.catalogs.actions)
        assertEquals(Vec3(1.0, 2.0, 3.0), prop.transform.position)
        assertEquals(EulerRotation(-90.0, 0.0, 0.0), prop.transform.rotation)
        assertEquals(Vec3(1.0, 1.0, 1.0), prop.transform.scale)
        assertEquals(null, prop.group)
        assertEquals(null, prop.initialAnimation)
        assertTrue(prop.visible)
    }

    @Test
    fun `new npc receives catalog bounds and deterministic defaults`() {
        val result =
            SceneMutations.createNpc(
                    actor,
                    LocalId("guide"),
                    AssetKey("grounds:editor/guide"),
                    PlayerPlacement(Vec3(4.0, 5.0, 6.0), 180.0),
                )
                .apply(binding.newDocument("grounds:test"), binding)

        val npc = assertSuccess(result).elements.single() as Npc
        assertEquals(Vec3(0.0, 2.25, 0.0), npc.labelOffset)
        assertEquals(LookBehavior.Fixed, npc.look)
        assertEquals(null, npc.label)
        assertEquals(null, npc.proximity)
        assertTrue(npc.bindings.isEmpty())
        assertEquals(LocalBounds(Vec3(0.0, 0.9, 0.0), Vec3(0.6, 1.8, 0.6)), npc.interactionBounds)
    }

    @Test
    fun `creation rejects wrong asset kind missing npc bounds and duplicate ids without changing original`() {
        val document =
            SceneMutations.createProp(
                    actor,
                    LocalId("marker"),
                    AssetKey("grounds:editor/marker"),
                    placement,
                )
                .apply(binding.newDocument("grounds:test"), binding)
                .documentOrThrow()

        listOf(
                SceneMutations.createProp(
                    actor,
                    LocalId("other"),
                    AssetKey("grounds:editor/guide"),
                    placement,
                ),
                SceneMutations.createNpc(
                    actor,
                    LocalId("guide"),
                    AssetKey("grounds:editor/no-bounds"),
                    placement,
                ),
                SceneMutations.createNpc(
                    actor,
                    LocalId("marker"),
                    AssetKey("grounds:editor/guide"),
                    placement,
                ),
            )
            .forEach { mutation ->
                val rejected = mutation.apply(document, binding)
                assertTrue(rejected is SceneMutationResult.Rejected)
                assertSame(document, rejected.document)
            }
    }

    @Test
    fun `typed mutations replace assets and edit transforms immutably`() {
        val document =
            SceneMutations.createProp(
                    actor,
                    LocalId("marker"),
                    AssetKey("grounds:editor/marker"),
                    placement,
                )
                .apply(binding.newDocument("grounds:test"), binding)
                .documentOrThrow()

        val replacement =
            SceneMutations.replacePropAsset(
                    actor,
                    LocalId("marker"),
                    AssetKey("grounds:editor/marker-alt"),
                )
                .apply(document, binding)
                .documentOrThrow()
        val moved =
            SceneMutations.setPosition(actor, LocalId("marker"), Vec3(4.0, 5.0, 6.0))
                .apply(replacement, binding)
                .documentOrThrow()
        val rotated =
            SceneMutations.addRotation(actor, LocalId("marker"), 200.0, 0.0, 0.0)
                .apply(moved, binding)
                .documentOrThrow()
        val scaled =
            SceneMutations.setUniformScale(actor, LocalId("marker"), 2.0)
                .apply(rotated, binding)
                .documentOrThrow()
        val clone =
            SceneMutations.clone(actor, LocalId("marker"), LocalId("copy"))
                .apply(scaled, binding)
                .documentOrThrow()

        assertEquals(Vec3(4.0, 5.0, 6.0), (scaled.elements.single() as Prop).transform.position)
        assertEquals(
            AssetKey("grounds:editor/marker-alt"),
            (scaled.elements.single() as Prop).asset,
        )
        assertEquals(-160.0, (scaled.elements.single() as Prop).transform.rotation.yaw)
        assertEquals(Vec3(2.0, 2.0, 2.0), (scaled.elements.single() as Prop).transform.scale)
        assertEquals(listOf("marker", "copy"), clone.elements.map { it.id.value })
        assertEquals(1, document.elements.size)
    }

    @Test
    fun `label and remove work for NPCs and reject wrong targets without changing original`() {
        val document =
            SceneMutations.createNpc(
                    actor,
                    LocalId("guide"),
                    AssetKey("grounds:editor/guide"),
                    placement,
                )
                .apply(binding.newDocument("grounds:test"), binding)
                .documentOrThrow()

        val labeled =
            SceneMutations.setLabel(actor, LocalId("guide"), Component.text("hello"))
                .apply(document, binding)
                .documentOrThrow()
        val removedSuccessful =
            SceneMutations.remove(actor, LocalId("guide")).apply(labeled, binding).documentOrThrow()
        val propDocument =
            SceneMutations.createProp(
                    actor,
                    LocalId("marker"),
                    AssetKey("grounds:editor/marker"),
                    placement,
                )
                .apply(binding.newDocument("grounds:test"), binding)
                .documentOrThrow()
        val labelResult =
            SceneMutations.setLabel(actor, LocalId("marker"), Component.text("hello"))
                .apply(propDocument, binding)
        val removed = SceneMutations.remove(actor, LocalId("missing")).apply(document, binding)

        assertEquals(Component.text("hello"), (labeled.elements.single() as Npc).label)
        assertTrue(removedSuccessful.elements.isEmpty())
        assertTrue(labelResult is SceneMutationResult.Rejected)
        assertSame(propDocument, labelResult.document)
        assertTrue(removed is SceneMutationResult.Rejected)
        assertSame(document, removed.document)
    }

    @Test
    fun `unrelated mutations preserve an unverified application action and action remains read only`() {
        val action = ApplicationAction(ActionKey("other:unknown"), emptyMap())
        val npc =
            Npc(
                LocalId("guide"),
                null,
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                body = AssetKey("grounds:editor/guide"),
                label = null,
                labelOffset = Vec3(0.0, 2.25, 0.0),
                look = LookBehavior.Fixed,
                initialAnimation = null,
                interactionBounds = LocalBounds(Vec3(0.0, 0.9, 0.0), Vec3(0.6, 1.8, 0.6)),
                proximity = null,
                bindings =
                    listOf(
                        TriggerBinding(SceneTrigger.LEFT_CLICK, emptyList(), 0, 0, listOf(action))
                    ),
            )
        val document = binding.newDocument("grounds:test", elements = listOf(npc))

        val changed =
            SceneMutations.setPosition(actor, LocalId("guide"), Vec3(7.0, 8.0, 9.0))
                .apply(document, binding)
                .documentOrThrow()
        val rejection = SceneMutations.remove(actor, LocalId("guide")).apply(changed, binding)

        assertSame(action, ((changed.elements.single() as Npc).bindings.single().actions.single()))
        assertTrue(rejection is SceneMutationResult.Rejected)
        assertSame(changed, rejection.document)
        assertFalse(binding.actionsVerified(changed))
    }

    private fun assertSuccess(result: SceneMutationResult): SceneDocument {
        assertTrue(result is SceneMutationResult.Success)
        return (result as SceneMutationResult.Success).document
    }

    private fun SceneMutationResult.documentOrThrow(): SceneDocument = assertSuccess(this)

    private companion object {
        val placement = PlayerPlacement(Vec3(0.0, 64.0, 0.0), 0.0)
        val testAssets =
            AssetCatalog(
                CatalogId("grounds:assets"),
                "6",
                CatalogVersionRange(CatalogId("grounds:resourcepacks"), "6", "6"),
                linkedMapOf(
                    AssetKey("grounds:editor/marker") to
                        AssetDefinition(
                            AssetKey("grounds:editor/marker"),
                            AssetKind.PROP,
                            emptySet(),
                            null,
                            emptyMap(),
                        ),
                    AssetKey("grounds:editor/marker-alt") to
                        AssetDefinition(
                            AssetKey("grounds:editor/marker-alt"),
                            AssetKind.PROP,
                            emptySet(),
                            null,
                            emptyMap(),
                        ),
                    AssetKey("grounds:editor/guide") to
                        AssetDefinition(
                            AssetKey("grounds:editor/guide"),
                            AssetKind.NPC_BODY,
                            emptySet(),
                            LocalBounds(Vec3(0.0, 0.9, 0.0), Vec3(0.6, 1.8, 0.6)),
                            emptyMap(),
                        ),
                    AssetKey("grounds:editor/no-bounds") to
                        AssetDefinition(
                            AssetKey("grounds:editor/no-bounds"),
                            AssetKind.NPC_BODY,
                            emptySet(),
                            null,
                            emptyMap(),
                        ),
                ),
            )
    }
}
