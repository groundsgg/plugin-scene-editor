package gg.grounds.scene.editor.mutation

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.validation.SaveEligibility
import gg.grounds.scene.editor.validation.SaveIneligibility
import gg.grounds.scene.editor.validation.SceneValidationState
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.ActionDefinition
import gg.grounds.scene.format.ActionKey
import gg.grounds.scene.format.ActionParameter
import gg.grounds.scene.format.ActionParameterType
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.AssetArgument
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetConstraints
import gg.grounds.scene.format.AssetDefinition
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogReference
import gg.grounds.scene.format.CatalogVersionRange
import gg.grounds.scene.format.CompositePart
import gg.grounds.scene.format.CompositeProp
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.LookBehavior
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.SceneCatalogReferences
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.StringArgument
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.TriggerBinding
import gg.grounds.scene.format.Vec3
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneMutationReviewRegressionTest {
    private val actor = UUID(4, 2)

    @Test
    fun `composite props are rejected unchanged by every first-slice element mutation`() {
        val binding = binding()
        val document = binding.newDocument("grounds:composite", elements = listOf(composite()))

        listOf(
                SceneMutations.setPosition(actor, LocalId("composite"), Vec3(1.0, 2.0, 3.0)),
                SceneMutations.placeHere(
                    actor,
                    LocalId("composite"),
                    PlayerPlacement(Vec3(1.0, 2.0, 3.0), 90.0),
                ),
                SceneMutations.addPosition(actor, LocalId("composite"), Vec3(1.0, 0.0, 0.0)),
                SceneMutations.setRotation(actor, LocalId("composite"), 1.0, 2.0, 3.0),
                SceneMutations.addRotation(actor, LocalId("composite"), 1.0, 2.0, 3.0),
                SceneMutations.setUniformScale(actor, LocalId("composite"), 2.0),
                SceneMutations.clone(actor, LocalId("composite"), LocalId("copy")),
                SceneMutations.remove(actor, LocalId("composite")),
            )
            .forEach { mutation ->
                val result = mutation.apply(document, binding)
                assertTrue(result is SceneMutationResult.Rejected)
                assertSame(document, result.document)
            }
    }

    @Test
    fun `creation with a nonfinite yaw rejects instead of throwing`() {
        val binding = binding()
        val document = binding.newDocument("grounds:test")

        listOf(
                SceneMutations.createProp(
                    actor,
                    LocalId("prop"),
                    AssetKey("grounds:editor/marker"),
                    PlayerPlacement(Vec3(0.0, 0.0, 0.0), Double.NaN),
                ),
                SceneMutations.createNpc(
                    actor,
                    LocalId("npc"),
                    AssetKey("grounds:editor/guide"),
                    PlayerPlacement(Vec3(0.0, 0.0, 0.0), Double.POSITIVE_INFINITY),
                ),
            )
            .forEach { mutation ->
                val result = mutation.apply(document, binding)
                assertTrue(result is SceneMutationResult.Rejected)
                assertEquals(SceneMutationRejection.INTRINSIC_INVALID, rejection(result).reason)
                assertSame(document, result.document)
            }
    }

    @Test
    fun `npc replace and all transform families use canonical immutable DTOs`() {
        val binding = binding()
        val original = createNpc(binding)
        val replaced =
            SceneMutations.replaceNpcBody(
                    actor,
                    LocalId("guide"),
                    AssetKey("grounds:editor/guide-alt"),
                )
                .apply(original, binding)
                .documentOrThrow()
        val here =
            SceneMutations.placeHere(
                    actor,
                    LocalId("guide"),
                    PlayerPlacement(Vec3(1.0, 2.0, 3.0), 270.0),
                )
                .apply(replaced, binding)
                .documentOrThrow()
        val moved =
            SceneMutations.addPosition(actor, LocalId("guide"), Vec3(1.0, -2.0, 0.5))
                .apply(here, binding)
                .documentOrThrow()
        val set =
            SceneMutations.setRotation(actor, LocalId("guide"), 540.0, -540.0, 360.0)
                .apply(moved, binding)
                .documentOrThrow()
        val added =
            SceneMutations.addRotation(actor, LocalId("guide"), 190.0, 190.0, -190.0)
                .apply(set, binding)
                .documentOrThrow()

        val npc = added.elements.single() as Npc
        assertEquals(AssetKey("grounds:editor/guide-alt"), npc.body)
        assertEquals(Vec3(2.0, 0.0, 3.5), npc.transform.position)
        assertEquals(EulerRotation(10.0, 10.0, 170.0), npc.transform.rotation)
        assertEquals(AssetKey("grounds:editor/guide"), (original.elements.single() as Npc).body)
    }

    @Test
    fun `invalid uniform scale rejects exactly the original document`() {
        val binding = binding()
        val document = createNpc(binding)

        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { scale ->
            val result =
                SceneMutations.setUniformScale(actor, LocalId("guide"), scale)
                    .apply(document, binding)
            assertTrue(result is SceneMutationResult.Rejected)
            assertEquals(SceneMutationRejection.INVALID_SCALE, rejection(result).reason)
            assertSame(document, result.document)
        }
    }

    @Test
    fun `catalog status and save eligibility distinguish exact mismatched and intrinsically invalid documents`() {
        val binding = binding()
        val exact = binding.newDocument("grounds:test")
        val mismatched =
            SceneDocument(
                exact.schemaVersion,
                exact.id,
                exact.metadata,
                SceneCatalogReferences(
                    exact.catalogs.assets,
                    CatalogReference(CatalogId("grounds:actions"), "other"),
                ),
                exact.groups,
                exact.elements,
            )
        val invalid =
            binding.newDocument("grounds:test", elements = listOf(prop("same"), prop("same")))

        assertTrue(binding.status(exact).isVerified)
        assertFalse(binding.status(mismatched).isVerified)
        assertTrue(SaveEligibility.from(SceneValidationState.of(exact, binding)).isEligible)
        assertEquals(
            setOf(SaveIneligibility.CATALOG_UNVERIFIED),
            SaveEligibility.from(SceneValidationState.of(mismatched, binding)).reasons,
        )
        assertEquals(
            setOf(SaveIneligibility.INTRINSIC_INVALID),
            SaveEligibility.from(SceneValidationState.of(invalid, binding)).reasons,
        )
    }

    @Test
    fun `application action clone and removal require matching pins and fully valid action arguments`() {
        val actionBinding = binding(actionCatalog())
        val valid =
            actionDocument(
                actionBinding,
                ApplicationAction(
                    ActionKey("grounds:award"),
                    mapOf(LocalId("asset") to AssetArgument(AssetKey("grounds:editor/marker"))),
                ),
            )
        val wrongPin =
            actionDocument(
                actionBinding,
                ApplicationAction(
                    ActionKey("grounds:award"),
                    mapOf(LocalId("asset") to AssetArgument(AssetKey("grounds:editor/marker"))),
                ),
                actionVersion = "other",
            )
        val missingArgument =
            actionDocument(actionBinding, ApplicationAction(ActionKey("grounds:award"), emptyMap()))
        val invalidArgument =
            actionDocument(
                actionBinding,
                ApplicationAction(
                    ActionKey("grounds:award"),
                    mapOf(LocalId("asset") to StringArgument("not-an-asset")),
                ),
            )
        val unknownAsset =
            actionDocument(
                actionBinding,
                ApplicationAction(
                    ActionKey("grounds:award"),
                    mapOf(LocalId("asset") to AssetArgument(AssetKey("grounds:editor/missing"))),
                ),
            )
        val wrongAssetKind =
            actionDocument(
                actionBinding,
                ApplicationAction(
                    ActionKey("grounds:award"),
                    mapOf(LocalId("asset") to AssetArgument(AssetKey("grounds:editor/guide"))),
                ),
            )
        val equalButAbsent =
            ApplicationAction(
                ActionKey("grounds:award"),
                mapOf(LocalId("asset") to AssetArgument(AssetKey("grounds:editor/marker"))),
            )

        assertTrue(actionBinding.actionsVerified(valid))
        assertFalse(actionBinding.actionVerified(valid, equalButAbsent))
        listOf(wrongPin, missingArgument, invalidArgument, unknownAsset, wrongAssetKind).forEach {
            document ->
            assertFalse(actionBinding.actionsVerified(document))
            listOf(
                    SceneMutations.clone(actor, LocalId("guide"), LocalId("copy")),
                    SceneMutations.remove(actor, LocalId("guide")),
                )
                .forEach { mutation ->
                    val result = mutation.apply(document, actionBinding)
                    assertTrue(result is SceneMutationResult.Rejected)
                    assertEquals(
                        SceneMutationRejection.READ_ONLY_APPLICATION_ACTION,
                        rejection(result).reason,
                    )
                    assertSame(document, result.document)
                }
        }
    }

    private fun createNpc(binding: SceneCatalogBinding): SceneDocument =
        SceneMutations.createNpc(
                actor,
                LocalId("guide"),
                AssetKey("grounds:editor/guide"),
                PlayerPlacement(Vec3(0.0, 0.0, 0.0), 0.0),
            )
            .apply(binding.newDocument("grounds:test"), binding)
            .documentOrThrow()

    private fun actionDocument(
        binding: SceneCatalogBinding,
        action: ApplicationAction,
        actionVersion: String = "1",
    ): SceneDocument {
        val npc =
            Npc(
                LocalId("guide"),
                null,
                transform(),
                body = AssetKey("grounds:editor/guide"),
                label = null,
                labelOffset = Vec3(0.0, 2.25, 0.0),
                look = LookBehavior.Fixed,
                initialAnimation = null,
                interactionBounds = GUIDE_BOUNDS,
                proximity = null,
                bindings =
                    listOf(
                        TriggerBinding(SceneTrigger.LEFT_CLICK, emptyList(), 0, 0, listOf(action))
                    ),
            )
        val base = binding.newDocument("grounds:actions", elements = listOf(npc))
        return SceneDocument(
            base.schemaVersion,
            base.id,
            base.metadata,
            SceneCatalogReferences(
                base.catalogs.assets,
                CatalogReference(CatalogId("grounds:actions"), actionVersion),
            ),
            base.groups,
            base.elements,
        )
    }

    private fun composite(): CompositeProp =
        CompositeProp(
            LocalId("composite"),
            null,
            transform(),
            parts =
                listOf(
                    CompositePart(LocalId("part"), AssetKey("grounds:editor/marker"), transform())
                ),
        )

    private fun prop(id: String) =
        Prop(
            LocalId(id),
            null,
            transform(),
            asset = AssetKey("grounds:editor/marker"),
            initialAnimation = null,
        )

    private fun transform() =
        Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))

    private fun binding(
        actions: ActionCatalog = ActionCatalog(CatalogId("grounds:actions"), "1", emptyMap())
    ) =
        SceneCatalogBinding(
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
                    AssetKey("grounds:editor/guide") to
                        AssetDefinition(
                            AssetKey("grounds:editor/guide"),
                            AssetKind.NPC_BODY,
                            emptySet(),
                            GUIDE_BOUNDS,
                            emptyMap(),
                        ),
                    AssetKey("grounds:editor/guide-alt") to
                        AssetDefinition(
                            AssetKey("grounds:editor/guide-alt"),
                            AssetKind.NPC_BODY,
                            emptySet(),
                            LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                            emptyMap(),
                        ),
                ),
            ),
            actions,
        )

    private fun actionCatalog() =
        ActionCatalog(
            CatalogId("grounds:actions"),
            "1",
            mapOf(
                ActionKey("grounds:award") to
                    ActionDefinition(
                        ActionKey("grounds:award"),
                        "Award",
                        "Award an asset",
                        mapOf(
                            LocalId("asset") to
                                ActionParameter(
                                    LocalId("asset"),
                                    ActionParameterType.ASSET,
                                    true,
                                    null,
                                    AssetConstraints(AssetKind.PROP),
                                )
                        ),
                    )
            ),
        )

    private fun SceneMutationResult.documentOrThrow(): SceneDocument {
        assertTrue(this is SceneMutationResult.Success)
        return (this as SceneMutationResult.Success).document
    }

    private fun rejection(result: SceneMutationResult): SceneMutationResult.Rejected {
        assertTrue(result is SceneMutationResult.Rejected)
        return result as SceneMutationResult.Rejected
    }

    private companion object {
        val GUIDE_BOUNDS = LocalBounds(Vec3(0.0, 0.9, 0.0), Vec3(0.6, 1.8, 0.6))
    }
}
