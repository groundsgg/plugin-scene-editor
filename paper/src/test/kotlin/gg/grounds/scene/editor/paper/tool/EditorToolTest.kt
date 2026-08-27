package gg.grounds.scene.editor.paper.tool

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit

class EditorToolTest {
    @AfterEach fun tearDown() = MockBukkit.unmock()

    @Test
    fun `accepts only the exact plugin tagged item`() {
        MockBukkit.mock()
        val plugin = MockBukkit.createMockPlugin()
        val tool = EditorTool(plugin)

        assertTrue(tool.isTool(tool.createItem()))
        assertFalse(tool.isTool(ItemStack(Material.STICK)))
        val wrongMarker = tool.createItem()
        wrongMarker.editMeta { meta ->
            meta.persistentDataContainer.set(
                NamespacedKey(plugin, "editor_tool"),
                PersistentDataType.BYTE,
                2.toByte(),
            )
        }
        assertFalse(tool.isTool(wrongMarker))
        val wrongMaterial = tool.createItem().withType(Material.STICK)
        assertFalse(tool.isTool(wrongMaterial))
        assertFalse(tool.isTool(null))
    }
}
