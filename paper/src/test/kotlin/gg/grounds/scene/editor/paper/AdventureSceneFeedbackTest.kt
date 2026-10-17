package gg.grounds.scene.editor.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

class AdventureSceneFeedbackTest {
    @Test
    fun `formats informational feedback as system chat`() {
        val sender = mock(CommandSender::class.java)

        AdventureSceneFeedback().send(sender, SceneMessage.SAVED)

        assertEquals(
            "[Grounds] General | Scene saved locally to scene.json.",
            sentPlainText(sender),
        )
    }

    @Test
    fun `formats error feedback without changing its context`() {
        val sender = mock(CommandSender::class.java)

        AdventureSceneFeedback()
            .send(
                sender,
                SceneMessage.SAVE_FAILED,
                "reason" to "I/O failure",
            )

        assertEquals("[Grounds] General | Scene save failed: I/O failure.", sentPlainText(sender))
    }

    private fun sentPlainText(sender: CommandSender): String {
        val message = ArgumentCaptor.forClass(Component::class.java)
        verify(sender).sendMessage(message.capture())
        return PlainTextComponentSerializer.plainText().serialize(message.value)
    }
}
