package gg.grounds.scene.editor.paper

import gg.grounds.i18n.BundleCheck
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
}
