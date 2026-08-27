package gg.grounds.scene.editor.tool

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutationResult
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TransformMathTest {
    private val transform =
        Transform(Vec3(1.0, 2.0, 3.0), EulerRotation(170.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))

    @Test
    fun `uses exact fine normal and coarse steps for each component family`() {
        assertEquals(listOf(0.01, 0.1, 1.0), amounts(TransformComponent.X))
        assertEquals(listOf(1.0, 5.0, 15.0), amounts(TransformComponent.YAW))
        assertEquals(listOf(0.01, 0.05, 0.25), amounts(TransformComponent.SCALE))
    }

    @Test
    fun `adjusts every component with signed steps and canonical rotation`() {
        assertEquals(
            0.99,
            TransformMath.adjust(transform, TransformComponent.X, TransformStep.FINE, -1).position.x,
        )
        assertEquals(
            2.1,
            TransformMath.adjust(transform, TransformComponent.Y, TransformStep.NORMAL, 1)
                .position
                .y,
        )
        assertEquals(
            4.0,
            TransformMath.adjust(transform, TransformComponent.Z, TransformStep.COARSE, 1)
                .position
                .z,
        )
        assertEquals(
            -175.0,
            TransformMath.adjust(transform, TransformComponent.YAW, TransformStep.COARSE, 1)
                .rotation
                .yaw,
        )
        assertEquals(
            -5.0,
            TransformMath.adjust(transform, TransformComponent.PITCH, TransformStep.NORMAL, -1)
                .rotation
                .pitch,
        )
        assertEquals(
            1.0,
            TransformMath.adjust(transform, TransformComponent.ROLL, TransformStep.FINE, 1)
                .rotation
                .roll,
        )
        assertEquals(
            Vec3(1.25, 1.25, 1.25),
            TransformMath.adjust(transform, TransformComponent.SCALE, TransformStep.COARSE, 1).scale,
        )
    }

    @Test
    fun `rejects invalid direction and non-positive scale`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransformMath.adjust(transform, TransformComponent.X, TransformStep.NORMAL, 0)
        }
        val tiny = transform.copy(scale = Vec3(0.01, 0.01, 0.01))
        assertThrows(IllegalArgumentException::class.java) {
            TransformMath.adjust(tiny, TransformComponent.SCALE, TransformStep.FINE, -1)
        }
    }

    @Test
    fun `tool mutations are exactly equivalent to command mutations`() {
        val catalogs = SceneCatalogBinding.production()
        val actor = UUID(1, 2)
        val id = LocalId("marker")
        val created =
            (SceneMutations.createProp(
                        actor,
                        id,
                        AssetKey("grounds:editor/marker"),
                        PlayerPlacement(transform.position, transform.rotation.yaw),
                    )
                    .apply(catalogs.newDocument("grounds:tool"), catalogs)
                    as SceneMutationResult.Success)
                .document

        TransformComponent.entries.forEach { component ->
            val current = (created.elements.single() as Prop).transform
            val tool =
                TransformMath.mutation(actor, id, current, component, TransformStep.NORMAL, 1)
                    .apply(created, catalogs) as SceneMutationResult.Success
            val adjusted = TransformMath.adjust(current, component, TransformStep.NORMAL, 1)
            val command =
                when (component) {
                    TransformComponent.X,
                    TransformComponent.Y,
                    TransformComponent.Z -> SceneMutations.setPosition(actor, id, adjusted.position)
                    TransformComponent.YAW,
                    TransformComponent.PITCH,
                    TransformComponent.ROLL ->
                        SceneMutations.setRotation(
                            actor,
                            id,
                            adjusted.rotation.yaw,
                            adjusted.rotation.pitch,
                            adjusted.rotation.roll,
                        )
                    TransformComponent.SCALE ->
                        SceneMutations.setUniformScale(actor, id, adjusted.scale.x)
                }.apply(created, catalogs) as SceneMutationResult.Success
            assertEquals(command.document, tool.document, component.name)
        }
    }

    private fun amounts(component: TransformComponent) =
        TransformStep.entries.map { TransformMath.amount(component, it) }
}
