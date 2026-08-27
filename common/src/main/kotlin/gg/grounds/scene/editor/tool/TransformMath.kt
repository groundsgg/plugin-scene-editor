package gg.grounds.scene.editor.tool

import gg.grounds.scene.editor.mutation.SceneMutation
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import java.util.UUID

/** Canonical transform arithmetic shared by the Paper tool and command-equivalence tests. */
object TransformMath {
    fun amount(component: TransformComponent, step: TransformStep): Double =
        when (component) {
            TransformComponent.X,
            TransformComponent.Y,
            TransformComponent.Z ->
                when (step) {
                    TransformStep.FINE -> 0.01
                    TransformStep.NORMAL -> 0.1
                    TransformStep.COARSE -> 1.0
                }
            TransformComponent.YAW,
            TransformComponent.PITCH,
            TransformComponent.ROLL ->
                when (step) {
                    TransformStep.FINE -> 1.0
                    TransformStep.NORMAL -> 5.0
                    TransformStep.COARSE -> 15.0
                }
            TransformComponent.SCALE ->
                when (step) {
                    TransformStep.FINE -> 0.01
                    TransformStep.NORMAL -> 0.05
                    TransformStep.COARSE -> 0.25
                }
        }

    fun adjust(
        transform: Transform,
        component: TransformComponent,
        step: TransformStep,
        direction: Int,
    ): Transform {
        require(direction == -1 || direction == 1) { "direction must be -1 or 1" }
        val delta = amount(component, step) * direction
        return when (component) {
            TransformComponent.X ->
                transform.copy(
                    position =
                        Vec3(
                            transform.position.x + delta,
                            transform.position.y,
                            transform.position.z,
                        )
                )
            TransformComponent.Y ->
                transform.copy(
                    position =
                        Vec3(
                            transform.position.x,
                            transform.position.y + delta,
                            transform.position.z,
                        )
                )
            TransformComponent.Z ->
                transform.copy(
                    position =
                        Vec3(
                            transform.position.x,
                            transform.position.y,
                            transform.position.z + delta,
                        )
                )
            TransformComponent.YAW ->
                transform.copy(
                    rotation =
                        EulerRotation(
                            transform.rotation.yaw + delta,
                            transform.rotation.pitch,
                            transform.rotation.roll,
                        )
                )
            TransformComponent.PITCH ->
                transform.copy(
                    rotation =
                        EulerRotation(
                            transform.rotation.yaw,
                            transform.rotation.pitch + delta,
                            transform.rotation.roll,
                        )
                )
            TransformComponent.ROLL ->
                transform.copy(
                    rotation =
                        EulerRotation(
                            transform.rotation.yaw,
                            transform.rotation.pitch,
                            transform.rotation.roll + delta,
                        )
                )
            TransformComponent.SCALE -> {
                val scale = transform.scale.x + delta
                require(scale.isFinite() && scale > 0.0) { "scale must remain positive and finite" }
                transform.copy(scale = Vec3(scale, scale, scale))
            }
        }
    }

    fun mutation(
        actor: UUID,
        target: LocalId,
        transform: Transform,
        component: TransformComponent,
        step: TransformStep,
        direction: Int,
    ): SceneMutation {
        val adjusted = adjust(transform, component, step, direction)
        return when (component) {
            TransformComponent.X,
            TransformComponent.Y,
            TransformComponent.Z -> {
                val before = transform.position
                val after = adjusted.position
                SceneMutations.addPosition(
                    actor,
                    target,
                    Vec3(after.x - before.x, after.y - before.y, after.z - before.z),
                )
            }
            TransformComponent.YAW,
            TransformComponent.PITCH,
            TransformComponent.ROLL -> {
                val amount = amount(component, step) * direction
                SceneMutations.addRotation(
                    actor,
                    target,
                    if (component == TransformComponent.YAW) amount else 0.0,
                    if (component == TransformComponent.PITCH) amount else 0.0,
                    if (component == TransformComponent.ROLL) amount else 0.0,
                )
            }
            TransformComponent.SCALE ->
                SceneMutations.setUniformScale(actor, target, adjusted.scale.x)
        }
    }
}
