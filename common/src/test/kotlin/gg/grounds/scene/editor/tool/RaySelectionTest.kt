package gg.grounds.scene.editor.tool

import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RaySelectionTest {
    private val ray = RaySelection.Ray(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))

    @Test
    fun `returns nearest bounded hit deterministically`() {
        val hit = RaySelection.nearest(ray, listOf(target("far", 5.0), target("near", 2.0)), 10.0)
        assertEquals("near", hit?.target?.id?.value)
        assertEquals(2.0, hit?.distance)
    }

    @Test
    fun `normalizes direction so distance is measured in world units`() {
        val scaledRay = RaySelection.Ray(Vec3(0.0, 0.0, 0.0), Vec3(2.0, 0.0, 0.0))

        assertNull(RaySelection.nearest(scaledRay, listOf(target("far", 5.0)), 4.0))
        assertEquals(
            5.0,
            RaySelection.nearest(scaledRay, listOf(target("far", 5.0)), 5.0)?.distance,
        )
    }

    @Test
    fun `uses id as stable tie breaker and excludes misses and distant targets`() {
        assertEquals(
            "a",
            RaySelection.nearest(ray, listOf(target("b", 2.0), target("a", 2.0)), 10.0)
                ?.target
                ?.id
                ?.value,
        )
        assertNull(RaySelection.nearest(ray, listOf(target("far", 5.0)), 4.0))
    }

    @Test
    fun `rejects invalid rays bounds and maximum distance`() {
        assertThrows(IllegalArgumentException::class.java) {
            RaySelection.Ray(Vec3(Double.NaN, 0.0, 0.0), Vec3(1.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RaySelection.Ray(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RaySelection.nearest(ray, emptyList(), Double.POSITIVE_INFINITY)
        }
    }

    private fun target(id: String, x: Double) =
        RaySelection.Target(
            LocalId(id),
            RaySelection.Bounds(Vec3(x, -1.0, -1.0), Vec3(x + 1.0, 1.0, 1.0)),
        )
}
