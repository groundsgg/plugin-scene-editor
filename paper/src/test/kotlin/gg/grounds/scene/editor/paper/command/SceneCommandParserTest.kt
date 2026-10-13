package gg.grounds.scene.editor.paper.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SceneCommandParserTest {
    @Test
    fun `rejects NaN and infinity before scene DTO construction`() {
        assertNull(SceneCommandParser.finite("NaN"))
        assertNull(SceneCommandParser.finite("Infinity"))
        assertNull(SceneCommandParser.finite("-Infinity"))
        assertEquals(1.25, SceneCommandParser.finite("1.25"))
    }

    @Test
    fun `bounce distance validates the raw decimal before double conversion`() {
        assertEquals(0.0, SceneCommandParser.bounceDistance("0"))
        assertEquals(1.5, SceneCommandParser.bounceDistance("1.5"))
        assertEquals(20.0, SceneCommandParser.bounceDistance("20"))
        assertNull(SceneCommandParser.bounceDistance("-1e-4000"))
        assertNull(SceneCommandParser.bounceDistance("20.0000000000000000000000000000000001"))
        assertNull(SceneCommandParser.bounceDistance("NaN"))
        assertNull(SceneCommandParser.bounceDistance(null))
    }

    @Test
    fun `paginates deterministically with an explicit page`() {
        val page = SceneCommandParser.page((1..45).map { "id-$it" }, 2, 20)

        assertEquals((21..40).map { "id-$it" }, page.entries)
        assertEquals(2, page.page)
        assertEquals(3, page.pages)
    }
}
