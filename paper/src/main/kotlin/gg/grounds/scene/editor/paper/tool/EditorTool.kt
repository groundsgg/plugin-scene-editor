package gg.grounds.scene.editor.paper.tool

import gg.grounds.i18n.Translations
import gg.grounds.scene.editor.paper.SceneMessage
import java.util.Locale
import net.kyori.adventure.audience.Audience
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin

/** Exact PDC identity for the future editor input listener. */
class EditorTool(plugin: JavaPlugin) {
    private val key = NamespacedKey(plugin, "editor_tool")
    private val messages =
        Translations.forBundle("gg.grounds.scene.editor.messages", javaClass.classLoader)

    fun createItem(audience: Audience? = null): ItemStack =
        ItemStack(Material.BLAZE_ROD).also { item ->
            item.editMeta { meta ->
                meta.displayName(
                    if (audience == null) messages.render(SceneMessage.TOOL_NAME, Locale.ENGLISH)
                    else messages.render(SceneMessage.TOOL_NAME, audience)
                )
                meta.persistentDataContainer.set(key, PersistentDataType.BYTE, 1.toByte())
            }
        }

    fun isTool(item: ItemStack?): Boolean =
        item?.type == Material.BLAZE_ROD &&
            item.itemMeta.persistentDataContainer.get(key, PersistentDataType.BYTE) == 1.toByte()
}
