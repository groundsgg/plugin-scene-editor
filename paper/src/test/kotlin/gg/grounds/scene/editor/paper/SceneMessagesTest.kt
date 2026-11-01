package gg.grounds.scene.editor.paper

import gg.grounds.i18n.BundleCheck
import gg.grounds.i18n.SystemCategory
import gg.grounds.i18n.SystemLevel
import gg.grounds.i18n.Translations
import java.util.Locale
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SceneMessagesTest {
    private fun clicks(component: Component): List<ClickEvent> =
        listOfNotNull(component.clickEvent()) + component.children().flatMap(::clicks)

    @Test
    fun `scene prompts offer create and help actions while discard requires submission`() {
        val messages =
            Translations.forBundle("gg.grounds.scene.editor.messages", javaClass.classLoader)
        listOf(Locale.ENGLISH, Locale.GERMAN).forEach { locale ->
            assertEquals(
                listOf(ClickEvent.suggestCommand("/scene create ")),
                clicks(messages.render(SceneMessage.NO_SOURCE, locale)),
            )
            assertEquals(
                listOf(ClickEvent.runCommand("/scene")),
                clicks(messages.render(SceneMessage.UNKNOWN, locale)),
            )
            val helpActions = clicks(messages.render(SceneMessage.HELP, locale))
            org.junit.jupiter.api.Assertions.assertTrue(
                helpActions.contains(ClickEvent.suggestCommand("/scene npc "))
            )
            org.junit.jupiter.api.Assertions.assertTrue(
                helpActions.contains(ClickEvent.runCommand("/scene validate"))
            )
            listOf(SceneMessage.DISCARD_CONFIRMATION, SceneMessage.LITERAL_CONFIRMATION).forEach {
                key ->
                assertEquals(
                    listOf(ClickEvent.suggestCommand("/scene recovery discard-and-reload confirm")),
                    clicks(messages.render(key, locale)),
                )
            }
        }
    }

    @Test
    fun `message bundles are complete`() {
        assertEquals(
            emptyList<String>(),
            BundleCheck.problems(
                "gg.grounds.scene.editor.messages",
                javaClass.classLoader,
                SceneMessage.entries,
                translations = listOf(Locale.GERMAN),
            ),
        )
    }

    @Test
    fun `editor messages use general context with independent outcome levels`() {
        val outcomes =
            listOf(
                SceneMessage.SAVED to SystemLevel.SUCCESS,
                SceneMessage.SAVE_FAILED to SystemLevel.ERROR,
                SceneMessage.DIRTY_CLOSE to SystemLevel.WARNING,
                SceneMessage.SAVING to SystemLevel.INFO,
            )
        outcomes.forEach { (message, expectedLevel) ->
            assertEquals(SystemCategory.GENERAL, message.category)
            assertEquals(expectedLevel, message.level)
        }
    }
}
