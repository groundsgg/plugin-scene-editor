package gg.grounds.scene.editor.paper

import gg.grounds.i18n.BundleCheck
import gg.grounds.i18n.SystemCategory
import gg.grounds.i18n.SystemLevel
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SceneMessagesTest {
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
