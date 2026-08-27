package gg.grounds.scene.editor.mutation

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.format.ActivationPolicy
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.LookBehavior
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneElement
import gg.grounds.scene.format.SceneValidation
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import java.util.UUID
import net.kyori.adventure.text.Component

data class PlayerPlacement(val position: Vec3, val yaw: Double)

/** Factories for the first-slice typed, immutable authoring operations. */
object SceneMutations {
    fun createProp(
        actor: UUID,
        id: LocalId,
        asset: AssetKey,
        placement: PlayerPlacement,
    ): SceneMutation = Create(actor, id, asset, placement, AssetKind.PROP)

    fun createNpc(
        actor: UUID,
        id: LocalId,
        body: AssetKey,
        placement: PlayerPlacement,
    ): SceneMutation = Create(actor, id, body, placement, AssetKind.NPC_BODY)

    fun replacePropAsset(actor: UUID, target: LocalId, asset: AssetKey): SceneMutation =
        ReplaceAsset(actor, target, asset, AssetKind.PROP)

    fun replaceNpcBody(actor: UUID, target: LocalId, body: AssetKey): SceneMutation =
        ReplaceAsset(actor, target, body, AssetKind.NPC_BODY)

    fun setPosition(actor: UUID, target: LocalId, position: Vec3): SceneMutation =
        TransformEdit(actor, target, "position.set") { it.copy(position = position) }

    fun placeHere(actor: UUID, target: LocalId, placement: PlayerPlacement): SceneMutation =
        TransformEdit(actor, target, "position.here") {
            it.copy(
                position = placement.position,
                rotation = EulerRotation(placement.yaw, 0.0, 0.0),
            )
        }

    fun addPosition(actor: UUID, target: LocalId, offset: Vec3): SceneMutation =
        TransformEdit(actor, target, "position.add") { transform ->
            transform.copy(
                position =
                    Vec3(
                        transform.position.x + offset.x,
                        transform.position.y + offset.y,
                        transform.position.z + offset.z,
                    )
            )
        }

    fun setRotation(
        actor: UUID,
        target: LocalId,
        yaw: Double,
        pitch: Double,
        roll: Double,
    ): SceneMutation =
        TransformEdit(actor, target, "rotation.set") {
            it.copy(rotation = EulerRotation(yaw, pitch, roll))
        }

    fun addRotation(
        actor: UUID,
        target: LocalId,
        yaw: Double,
        pitch: Double,
        roll: Double,
    ): SceneMutation =
        TransformEdit(actor, target, "rotation.add") { transform ->
            transform.copy(
                rotation =
                    EulerRotation(
                        transform.rotation.yaw + yaw,
                        transform.rotation.pitch + pitch,
                        transform.rotation.roll + roll,
                    )
            )
        }

    fun setUniformScale(actor: UUID, target: LocalId, scale: Double): SceneMutation =
        ScaleEdit(actor, target, scale)

    fun clone(actor: UUID, target: LocalId, copyId: LocalId): SceneMutation =
        Clone(actor, target, copyId)

    fun setLabel(actor: UUID, target: LocalId, label: Component?): SceneMutation =
        LabelEdit(actor, target, label)

    fun remove(actor: UUID, target: LocalId): SceneMutation = Remove(actor, target)

    private data class Create(
        override val actor: UUID,
        private val id: LocalId,
        private val asset: AssetKey,
        private val placement: PlayerPlacement,
        private val kind: AssetKind,
    ) : SceneMutation {
        override val name = if (kind == AssetKind.PROP) "prop.create" else "npc.create"
        override val target: LocalId? = null

        override fun apply(
            document: SceneDocument,
            catalogs: SceneCatalogBinding,
        ): SceneMutationResult {
            if (document.elements.any { it.id == id })
                return rejected(document, SceneMutationRejection.DUPLICATE_ELEMENT_ID)
            val definition =
                catalogs.assets.assets[asset]
                    ?: return rejected(document, SceneMutationRejection.UNKNOWN_ASSET)
            if (definition.kind != kind)
                return rejected(document, SceneMutationRejection.WRONG_ASSET_KIND)
            return try {
                val transform =
                    Transform(
                        placement.position,
                        EulerRotation(placement.yaw, 0.0, 0.0),
                        UNIT_SCALE,
                    )
                val element =
                    when (kind) {
                        AssetKind.PROP ->
                            Prop(id, null, transform, true, ActivationPolicy.AUTOMATIC, asset, null)
                        AssetKind.NPC_BODY -> {
                            val bounds =
                                definition.defaultBounds
                                    ?: return rejected(
                                        document,
                                        SceneMutationRejection.MISSING_NPC_BOUNDS,
                                    )
                            Npc(
                                id,
                                null,
                                transform,
                                true,
                                ActivationPolicy.AUTOMATIC,
                                asset,
                                null,
                                NPC_LABEL_OFFSET,
                                LookBehavior.Fixed,
                                null,
                                bounds,
                                null,
                                emptyList(),
                            )
                        }
                        else -> return rejected(document, SceneMutationRejection.WRONG_ASSET_KIND)
                    }
                accepted(document, document.withElements(document.elements + element))
            } catch (_: IllegalArgumentException) {
                rejected(document, SceneMutationRejection.INTRINSIC_INVALID)
            }
        }
    }

    private data class ReplaceAsset(
        override val actor: UUID,
        override val target: LocalId,
        private val asset: AssetKey,
        private val expectedKind: AssetKind,
    ) : SceneMutation {
        override val name = "asset.replace"

        override fun apply(
            document: SceneDocument,
            catalogs: SceneCatalogBinding,
        ): SceneMutationResult {
            val index = document.elements.indexOfFirst { it.id == target }
            if (index < 0) return rejected(document, SceneMutationRejection.ELEMENT_NOT_FOUND)
            val definition =
                catalogs.assets.assets[asset]
                    ?: return rejected(document, SceneMutationRejection.UNKNOWN_ASSET)
            if (definition.kind != expectedKind)
                return rejected(document, SceneMutationRejection.WRONG_ASSET_KIND)
            val element = document.elements[index]
            val replacement =
                when {
                    element is Prop && expectedKind == AssetKind.PROP -> element.copy(asset = asset)
                    element is Npc && expectedKind == AssetKind.NPC_BODY -> {
                        val bounds =
                            definition.defaultBounds
                                ?: return rejected(
                                    document,
                                    SceneMutationRejection.MISSING_NPC_BOUNDS,
                                )
                        Npc(
                            element.id,
                            element.group,
                            element.transform,
                            element.visible,
                            element.activation,
                            asset,
                            element.label,
                            element.labelOffset,
                            element.look,
                            element.initialAnimation,
                            bounds,
                            element.proximity,
                            element.bindings,
                        )
                    }
                    else -> return rejected(document, SceneMutationRejection.WRONG_ELEMENT_KIND)
                }
            return accepted(document, document.withElement(index, replacement))
        }
    }

    private data class TransformEdit(
        override val actor: UUID,
        override val target: LocalId,
        override val name: String,
        private val edit: (Transform) -> Transform,
    ) : SceneMutation {
        override fun apply(
            document: SceneDocument,
            catalogs: SceneCatalogBinding,
        ): SceneMutationResult =
            changeElement(document, target) { element ->
                withTransform(element, edit(element.transform))
            }
    }

    private data class ScaleEdit(
        override val actor: UUID,
        override val target: LocalId,
        private val scale: Double,
    ) : SceneMutation {
        override val name = "scale.set"

        override fun apply(
            document: SceneDocument,
            catalogs: SceneCatalogBinding,
        ): SceneMutationResult {
            if (!scale.isFinite() || scale <= 0.0)
                return rejected(document, SceneMutationRejection.INVALID_SCALE)
            return changeElement(document, target) { element ->
                withTransform(element, element.transform.copy(scale = Vec3(scale, scale, scale)))
            }
        }
    }

    private data class Clone(
        override val actor: UUID,
        override val target: LocalId,
        private val copyId: LocalId,
    ) : SceneMutation {
        override val name = "element.clone"

        override fun apply(
            document: SceneDocument,
            catalogs: SceneCatalogBinding,
        ): SceneMutationResult {
            if (document.elements.any { it.id == copyId })
                return rejected(document, SceneMutationRejection.DUPLICATE_ELEMENT_ID)
            val source =
                document.elements.firstOrNull { it.id == target }
                    ?: return rejected(document, SceneMutationRejection.ELEMENT_NOT_FOUND)
            if (source !is Prop && source !is Npc)
                return rejected(document, SceneMutationRejection.WRONG_ELEMENT_KIND)
            if (hasUnverifiedApplicationAction(source, document, catalogs))
                return rejected(document, SceneMutationRejection.READ_ONLY_APPLICATION_ACTION)
            val clone = withId(source, copyId)
            return accepted(document, document.withElements(document.elements + clone))
        }
    }

    private data class LabelEdit(
        override val actor: UUID,
        override val target: LocalId,
        private val label: Component?,
    ) : SceneMutation {
        override val name = "npc.label.set"

        override fun apply(
            document: SceneDocument,
            catalogs: SceneCatalogBinding,
        ): SceneMutationResult =
            changeElement(document, target) { element ->
                if (element !is Npc) null
                else
                    Npc(
                        element.id,
                        element.group,
                        element.transform,
                        element.visible,
                        element.activation,
                        element.body,
                        label,
                        element.labelOffset,
                        element.look,
                        element.initialAnimation,
                        element.interactionBounds,
                        element.proximity,
                        element.bindings,
                    )
            }
    }

    private data class Remove(override val actor: UUID, override val target: LocalId) :
        SceneMutation {
        override val name = "element.remove"

        override fun apply(
            document: SceneDocument,
            catalogs: SceneCatalogBinding,
        ): SceneMutationResult {
            val element =
                document.elements.firstOrNull { it.id == target }
                    ?: return rejected(document, SceneMutationRejection.ELEMENT_NOT_FOUND)
            if (element !is Prop && element !is Npc)
                return rejected(document, SceneMutationRejection.WRONG_ELEMENT_KIND)
            if (hasUnverifiedApplicationAction(element, document, catalogs))
                return rejected(document, SceneMutationRejection.READ_ONLY_APPLICATION_ACTION)
            return accepted(
                document,
                document.withElements(document.elements.filterNot { it.id == target }),
            )
        }
    }

    private fun changeElement(
        document: SceneDocument,
        target: LocalId,
        edit: (SceneElement) -> SceneElement?,
    ): SceneMutationResult {
        val index = document.elements.indexOfFirst { it.id == target }
        if (index < 0) return rejected(document, SceneMutationRejection.ELEMENT_NOT_FOUND)
        if (document.elements[index] !is Prop && document.elements[index] !is Npc)
            return rejected(document, SceneMutationRejection.WRONG_ELEMENT_KIND)
        val replacement =
            try {
                edit(document.elements[index])
            } catch (_: IllegalArgumentException) {
                return rejected(document, SceneMutationRejection.INTRINSIC_INVALID)
            } ?: return rejected(document, SceneMutationRejection.WRONG_ELEMENT_KIND)
        return accepted(document, document.withElement(index, replacement))
    }

    private fun accepted(original: SceneDocument, candidate: SceneDocument): SceneMutationResult =
        try {
            if (SceneValidation.validateIntrinsic(candidate).isValid)
                SceneMutationResult.Success(candidate)
            else rejected(original, SceneMutationRejection.INTRINSIC_INVALID)
        } catch (_: IllegalArgumentException) {
            rejected(original, SceneMutationRejection.INTRINSIC_INVALID)
        }

    private fun rejected(document: SceneDocument, reason: SceneMutationRejection) =
        SceneMutationResult.Rejected(document, reason)

    private fun withTransform(element: SceneElement, transform: Transform): SceneElement =
        when (element) {
            is Prop -> element.copy(transform = transform)
            is Npc ->
                Npc(
                    element.id,
                    element.group,
                    transform,
                    element.visible,
                    element.activation,
                    element.body,
                    element.label,
                    element.labelOffset,
                    element.look,
                    element.initialAnimation,
                    element.interactionBounds,
                    element.proximity,
                    element.bindings,
                )
            else -> element
        }

    private fun withId(element: SceneElement, id: LocalId): SceneElement =
        when (element) {
            is Prop -> element.copy(id = id)
            is Npc ->
                Npc(
                    id,
                    element.group,
                    element.transform,
                    element.visible,
                    element.activation,
                    element.body,
                    element.label,
                    element.labelOffset,
                    element.look,
                    element.initialAnimation,
                    element.interactionBounds,
                    element.proximity,
                    element.bindings,
                )
            else -> element
        }

    private fun hasUnverifiedApplicationAction(
        element: SceneElement,
        document: SceneDocument,
        catalogs: SceneCatalogBinding,
    ): Boolean =
        (element as? Npc)
            ?.bindings
            .orEmpty()
            .flatMap { it.actions }
            .filterIsInstance<ApplicationAction>()
            .any { action -> !catalogs.actionVerified(document, action) }

    private fun SceneDocument.withElements(elements: List<SceneElement>) =
        SceneDocument(schemaVersion, id, metadata, catalogs, groups, elements)

    private fun SceneDocument.withElement(index: Int, element: SceneElement): SceneDocument =
        withElements(
            elements.mapIndexed { current, value -> if (current == index) element else value }
        )

    private val UNIT_SCALE = Vec3(1.0, 1.0, 1.0)
    private val NPC_LABEL_OFFSET = Vec3(0.0, 2.25, 0.0)
}
