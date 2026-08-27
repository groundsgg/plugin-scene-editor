package gg.grounds.scene.editor.paper

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.editor.paper.preview.PaperPreviewAdapter
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import java.util.UUID
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PaperLifecycleListenerTest {
    private val worldId = UUID(1, 1)
    private val playerId = UUID(2, 2)
    private val catalogs = SceneCatalogBinding.production()

    @Test
    fun `world change releases selection and old-world preview`() {
        val sessions = selectedSession()
        val previews = mock(PaperPreviewAdapter::class.java)
        val cleared = mutableListOf<UUID>()
        val listener = PaperPlayerLifecycleListener(sessions, previews, cleared::add)
        val from = mock(World::class.java)
        `when`(from.uid).thenReturn(worldId)
        val player = mock(Player::class.java)
        `when`(player.uniqueId).thenReturn(playerId)

        listener.onWorldChange(PlayerChangedWorldEvent(player, from))

        assertEquals(null, sessions.selection(worldId, playerId))
        verify(previews).clearViewer(worldId, playerId)
        assertEquals(listOf(playerId), cleared)
    }

    @Test
    fun `world unload closes dirty session and removes every world preview`() {
        val sessions = selectedSession()
        sessions.deselect(worldId, playerId)
        val previews = mock(PaperPreviewAdapter::class.java)
        val plugin = mock(JavaPlugin::class.java)
        `when`(plugin.logger).thenReturn(java.util.logging.Logger.getAnonymousLogger())
        val server = mock(org.bukkit.Server::class.java)
        `when`(plugin.server).thenReturn(server)
        val player = mock(Player::class.java)
        `when`(server.getPlayer(playerId)).thenReturn(player)
        val listener = PaperWorldLifecycleListener(plugin, sessions, previews)
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(worldId)

        listener.onWorldUnload(WorldUnloadEvent(world))

        assertEquals(null, sessions.session(worldId))
        verify(previews).clearWorld(worldId)
        assertTrue(
            org.mockito.Mockito.mockingDetails(player).invocations.any {
                it.method.name == "sendMessage"
            }
        )
    }

    private fun selectedSession(): EditorSessionService {
        val sessions = EditorSessionService(catalogs)
        sessions.open(worldId, catalogs.newDocument("grounds:lifecycle"))
        assertTrue(
            sessions
                .mutate(
                    worldId,
                    SceneMutations.createProp(
                        playerId,
                        LocalId("marker"),
                        AssetKey("grounds:editor/marker"),
                        PlayerPlacement(Vec3(0.0, 0.0, 0.0), 0.0),
                    ),
                )
                .accepted
        )
        sessions.select(worldId, playerId, LocalId("marker"))
        return sessions
    }
}
