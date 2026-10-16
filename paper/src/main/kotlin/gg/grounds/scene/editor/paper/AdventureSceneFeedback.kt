package gg.grounds.scene.editor.paper

import gg.grounds.i18n.Translations
import gg.grounds.scene.editor.paper.command.SceneCommandFeedback
import org.bukkit.command.CommandSender

class AdventureSceneFeedback : SceneCommandFeedback {
    private val messages =
        Translations.forBundle("gg.grounds.scene.editor.messages", javaClass.classLoader)

    override fun send(
        sender: CommandSender,
        message: SceneMessage,
        vararg args: Pair<String, Any>,
    ) {
        val localizedArgs =
            args
                .map { (key, value) ->
                    key to if (value is SceneMessage) messages.render(value, sender) else value
                }
                .toTypedArray()
        messages.send(sender, message, *localizedArgs)
    }
}
