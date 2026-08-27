package gg.grounds.scene.editor.paper.command

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneCommandAuthorizerTest {
    @Test
    fun `requires the exact hierarchical permission for mutations`() {
        val authorizer = SceneCommandAuthorizer { permission ->
            permission == "grounds.scene.prop.create"
        }

        assertTrue(
            authorizer.isAllowed(listOf("prop", "marker", "create", "grounds:editor/marker"))
        )
        assertFalse(authorizer.isAllowed(listOf("prop", "marker", "remove")))
    }

    @Test
    fun `permits catalogs status with its dedicated permission`() {
        val authorizer = SceneCommandAuthorizer { permission ->
            permission == "grounds.scene.catalogs.status"
        }

        assertTrue(authorizer.isAllowed(listOf("catalogs", "status")))
    }

    @Test
    fun `transform subcommands require their exact leaf permission`() {
        val authorizer = SceneCommandAuthorizer { it == "grounds.scene.prop.position.here" }

        assertTrue(authorizer.isAllowed(listOf("PROP", "marker", "POSITION", "HERE")))
        assertFalse(authorizer.isAllowed(listOf("prop", "marker", "position", "set")))
    }

    @Test
    fun `override administrators may reach explicit lease release`() {
        val authorizer = SceneCommandAuthorizer { it == "grounds.scene.lease.override" }

        assertTrue(authorizer.isAllowed(listOf("lease", "release", "marker")))
    }
}
