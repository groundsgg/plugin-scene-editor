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
    fun `paginates deterministically with an explicit page`() {
        val page = SceneCommandParser.page((1..45).map { "id-$it" }, 2, 20)

        assertEquals((21..40).map { "id-$it" }, page.entries)
        assertEquals(2, page.page)
        assertEquals(3, page.pages)
    }
}
