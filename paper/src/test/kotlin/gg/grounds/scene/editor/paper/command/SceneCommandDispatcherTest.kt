package gg.grounds.scene.editor.paper.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SceneCommandDispatcherTest {
    @Test
    fun `catalog status is the sole console route`() =
        assertEquals(
            SceneCommandDispatcher.Route.CatalogStatus,
            SceneCommandDispatcher.route(listOf("catalogs", "status")),
        )

    @Test
    fun `discard requires literal confirm`() {
        assertEquals(
            SceneCommandDispatcher.Route.RecoveryDiscard(false),
            SceneCommandDispatcher.route(listOf("recovery", "discard-and-reload", "yes")),
        )
        assertEquals(
            SceneCommandDispatcher.Route.RecoveryDiscard(true),
            SceneCommandDispatcher.route(listOf("recovery", "discard-and-reload", "confirm")),
        )
    }

    @Test
    fun `routes element mutations without reconstructing DTOs`() =
        assertEquals(
            SceneCommandDispatcher.Route.Element("npc", "rotation"),
            SceneCommandDispatcher.route(listOf("npc", "guide", "rotation", "set", "0", "0", "0")),
        )

    @Test
    fun `rejects incomplete and excess command arguments`() {
        listOf(
                listOf("create", "id"),
                listOf("info", "extra"),
                listOf("catalogs", "status", "extra"),
                listOf("prop", "id", "position", "set", "0", "0"),
                listOf("npc", "id", "rotation", "add", "0", "0"),
                listOf("prop", "id", "scale", "set", "1", "extra"),
                listOf("npc", "id", "trigger"),
                listOf("recovery", "discard-and-reload"),
            )
            .forEach { args ->
                assertEquals(
                    SceneCommandDispatcher.Route.Invalid,
                    SceneCommandDispatcher.route(args),
                )
            }
    }

    @Test
    fun `accepts every prop and npc mutation shape`() {
        val operations =
            listOf(
                listOf("create", "grounds:editor/marker"),
                listOf("select"),
                listOf("position", "set", "1", "2", "3"),
                listOf("position", "here"),
                listOf("position", "add", "1", "2", "3"),
                listOf("rotation", "set", "1", "2", "3"),
                listOf("rotation", "add", "1", "2", "3"),
                listOf("scale", "set", "1"),
                listOf("clone", "copy"),
                listOf("remove"),
            )
        listOf("prop", "npc").forEach { kind ->
            operations.forEach { operation ->
                assertEquals(
                    SceneCommandDispatcher.Route.Element(kind, operation.first()),
                    SceneCommandDispatcher.route(listOf(kind, "id") + operation),
                )
            }
        }
        assertEquals(
            SceneCommandDispatcher.Route.Element("npc", "label"),
            SceneCommandDispatcher.route(listOf("npc", "id", "label", "set", "Guide")),
        )
    }
}
