package gg.grounds.scene.editor.paper.tool

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Exact PDC identity for the future editor input listener. */
class EditorTool(plugin: JavaPlugin) {
    private val key = NamespacedKey(plugin, "editor_tool")

    fun createItem(): ItemStack =
        ItemStack(Material.BLAZE_ROD).also { item ->
            item.editMeta { meta ->
                meta.displayName(Component.text("Scene Editor Tool"))
                meta.persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte())
            }
        }

    fun isTool(item: ItemStack?): Boolean =
        item?.type == Material.BLAZE_ROD &&
            item.itemMeta.persistentDataContainer.get(key, PersistentDataType.BYTE) == 1.toByte()
}
