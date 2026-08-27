package gg.grounds.scene.editor.tool

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure, deterministic nearest-hit selection over axis-aligned scene hit boxes. */
object RaySelection {
    fun nearest(ray: Ray, targets: Iterable<Target>, maximumDistance: Double): Hit? {
        require(maximumDistance.isFinite() && maximumDistance >= 0.0) {
            "maximumDistance must be finite and non-negative"
        }
        val direction = ray.normalizedDirection()
        return targets
            .mapNotNull { target -> intersect(ray.origin, direction, target, maximumDistance) }
            .minWithOrNull(compareBy<Hit>({ it.distance }, { it.target.id.value }))
    }

    private fun intersect(
        origin: Vec3,
        direction: Vec3,
        target: Target,
        maximumDistance: Double,
    ): Hit? {
        var near = 0.0
        var far = maximumDistance
        listOf(
                Triple(origin.x, direction.x, target.bounds.min.x to target.bounds.max.x),
                Triple(origin.y, direction.y, target.bounds.min.y to target.bounds.max.y),
                Triple(origin.z, direction.z, target.bounds.min.z to target.bounds.max.z),
            )
            .forEach { (component, rayDirection, limits) ->
                if (rayDirection == 0.0) {
                    if (component < limits.first || component > limits.second) return null
                } else {
                    val first = (limits.first - component) / rayDirection
                    val second = (limits.second - component) / rayDirection
                    near = max(near, min(first, second))
                    far = min(far, max(first, second))
                    if (near > far) return null
                }
            }
        return Hit(target, near)
    }

    data class Ray(val origin: Vec3, val direction: Vec3) {
        init {
            require(finite(origin) && finite(direction)) { "ray values must be finite" }
            require(direction.x != 0.0 || direction.y != 0.0 || direction.z != 0.0) {
                "ray direction must be non-zero"
            }
        }

        internal fun normalizedDirection(): Vec3 {
            val length =
                sqrt(
                    direction.x * direction.x +
                        direction.y * direction.y +
                        direction.z * direction.z
                )
            require(length.isFinite() && length > 0.0) { "ray direction magnitude must be finite" }
            return Vec3(direction.x / length, direction.y / length, direction.z / length)
        }
    }

    data class Bounds(val min: Vec3, val max: Vec3) {
        init {
            require(finite(min) && finite(max)) { "bounds must be finite" }
            require(min.x <= max.x && min.y <= max.y && min.z <= max.z) { "bounds must be ordered" }
        }
    }

    data class Target(val id: LocalId, val bounds: Bounds)

    data class Hit(val target: Target, val distance: Double)

    private fun finite(vector: Vec3) =
        vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()
}
