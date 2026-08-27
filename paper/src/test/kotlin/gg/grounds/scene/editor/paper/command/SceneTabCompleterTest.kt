package gg.grounds.scene.editor.paper.command

import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SceneTabCompleterTest {
    private val completer = SceneTabCompleter { npc ->
        if (npc) listOf("grounds:editor/guide") else listOf("grounds:editor/marker")
    }

    @Test
    fun `completes partial root paths deterministically`() {
        assertEquals(listOf("prop"), completer.complete(null, arrayOf("pr")))
    }

    @Test
    fun `completes only the required asset kind`() {
        assertEquals(
            listOf("grounds:editor/marker"),
            completer.complete(null, arrayOf("prop", "id", "create", "grounds:")),
        )
        assertEquals(
            listOf("grounds:editor/guide"),
            completer.complete(null, arrayOf("npc", "id", "create", "grounds:")),
        )
    }

    @Test
    fun `completes mixed case command paths`() {
        assertEquals(listOf("label"), completer.complete(null, arrayOf("NPC", "id", "la")))
        assertEquals(
            listOf("add", "here", "set"),
            completer.complete(null, arrayOf("PROP", "id", "POSITION", "")),
        )
    }

    @Test
    fun `filters mixed case paths using normalized leaf permissions`() {
        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("grounds.scene.prop.position.here")).thenReturn(true)

        assertEquals(
            listOf("here"),
            completer.complete(sender, arrayOf("PROP", "id", "POSITION", "")),
        )
    }

    @Test
    fun `keeps a partial operation when any exact descendant leaf is permitted`() {
        val sender = mock(CommandSender::class.java)
        `when`(sender.hasPermission("grounds.scene.prop.position.set")).thenReturn(true)

        assertEquals(
            listOf("position"),
            completer.complete(sender, arrayOf("prop", "marker", "pos")),
        )
    }

    @Test
    fun `completes only permitted kind scoped element ids and keeps list independent`() {
        val sender = mock(CommandSender::class.java)
        val completer =
            SceneTabCompleter(
                { emptyList<String>() },
                { _, npc -> if (npc) listOf("guide") else listOf("marker") },
            )
        `when`(sender.hasPermission("grounds.scene.prop.select")).thenReturn(true)
        `when`(sender.hasPermission("grounds.scene.prop.list")).thenReturn(true)

        assertEquals(listOf("list", "marker"), completer.complete(sender, arrayOf("prop", "")))
        assertEquals(emptyList<String>(), completer.complete(sender, arrayOf("npc", "")))

        `when`(sender.hasPermission("grounds.scene.prop.select")).thenReturn(false)
        assertEquals(listOf("list"), completer.complete(sender, arrayOf("prop", "")))
    }
}
