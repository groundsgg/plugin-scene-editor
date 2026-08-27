package gg.grounds.scene.editor.paper

import gg.grounds.scene.editor.paper.command.SceneCommandFeedback
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

class AdventureSceneFeedback : SceneCommandFeedback {
    override fun info(sender: CommandSender, message: String) =
        sender.sendMessage(Component.text(message))

    override fun error(sender: CommandSender, message: String) =
        sender.sendMessage(Component.text(message))
}
