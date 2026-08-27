package gg.grounds.scene.editor.paper.command

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Route-shape contract; Bukkit execution itself is covered by the lifecycle adapter suite. */
class SceneCommandTest {
    private val allowed = SceneCommandAuthorizer { true }

    @Test
    fun `accepts every first slice command route`() {
        val routes =
            listOf(
                listOf("create"),
                listOf("info"),
                listOf("validate"),
                listOf("save"),
                listOf("reload"),
                listOf("history"),
                listOf("undo"),
                listOf("redo"),
                listOf("tool", "give"),
                listOf("recovery", "backup-and-create"),
                listOf("recovery", "export"),
                listOf("recovery", "discard-and-reload"),
                listOf("catalogs", "status"),
                listOf("lease", "status"),
                listOf("lease", "release"),
            ) +
                listOf("prop", "npc").flatMap { kind ->
                    listOf(
                            "list",
                            "create",
                            "select",
                            "position",
                            "rotation",
                            "scale",
                            "clone",
                            "remove",
                        )
                        .map { operation ->
                            if (operation == "list") listOf(kind, operation)
                            else listOf(kind, "id", operation)
                        } + if (kind == "npc") listOf(listOf("npc", "id", "label")) else emptyList()
                }
        routes.forEach { assertTrue(allowed.isAllowed(it), "missing route: $it") }
    }

    @Test
    fun `rejects unsupported and incomplete routes`() {
        assertFalse(allowed.isAllowed(listOf("catalogs", "reload")))
        assertFalse(allowed.isAllowed(listOf("prop")))
        assertFalse(allowed.isAllowed(listOf("npc", "id", "trigger")))
    }
}
