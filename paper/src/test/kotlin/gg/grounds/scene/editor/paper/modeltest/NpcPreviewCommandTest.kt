package gg.grounds.scene.editor.paper.modeltest

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.paper.preview.*
import gg.grounds.scene.format.AssetKey
import java.util.UUID
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitScheduler
import org.bukkit.scheduler.BukkitTask
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class NpcPreviewCommandTest {
    @Test
    fun `gallery waits for its addon pack and ignores late status from a previous preview`() {
        val catalogs = SceneCatalogBinding.production()
        assumeTrue(AssetKey("grounds:npc/townfolk/builder") in catalogs.assets.assets)
        val plugin = mock(JavaPlugin::class.java)
        val server = mock(Server::class.java)
        val scheduler = mock(BukkitScheduler::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(scheduler)
        `when`(scheduler.runTaskTimer(eq(plugin), any(Runnable::class.java), anyLong(), anyLong()))
            .thenReturn(mock(BukkitTask::class.java))
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID.randomUUID())
        val player = mock(Player::class.java)
        val id = UUID.randomUUID()
        `when`(player.uniqueId).thenReturn(id)
        `when`(player.world).thenReturn(world)
        `when`(player.location).thenReturn(Location(world, 0.0, 140.0, 0.0))
        `when`(player.eyeLocation).thenReturn(Location(world, 0.0, 141.62, 0.0))
        `when`(player.isOnline).thenReturn(true)
        `when`(player.hasPermission("grounds.scene.edit")).thenReturn(true)
        `when`(server.getPlayer(id)).thenReturn(player)
        var creates = 0
        var advances = 0
        val previews =
            PaperPreviewAdapter(
                PreviewEntityFactory { _, _ ->
                    creates++
                    PreviewHandle(emptyList()) { advances++ }
                }
            )
        val gallery =
            NpcPreviewGallery(
                plugin,
                catalogs,
                previews,
                PreviewPack("https://cdn.grounds.gg/test.zip", "a".repeat(40)),
            )
        val command = mock(Command::class.java)
        gallery.onCommand(player, command, "npcpreview", emptyArray())
        gallery.tick()
        assertEquals(0, creates)
        val ids = ArgumentCaptor.forClass(UUID::class.java)
        verify(player)
            .addResourcePack(
                ids.capture(),
                anyString(),
                any(ByteArray::class.java),
                anyString(),
                eq(false),
            )
        val first = ids.value
        gallery.packStatus(
            PlayerResourcePackStatusEvent(
                player,
                UUID.randomUUID(),
                PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED,
            )
        )
        gallery.tick()
        assertEquals(0, creates)
        gallery.packStatus(
            PlayerResourcePackStatusEvent(
                player,
                first,
                PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED,
            )
        )
        gallery.tick()
        assertEquals(5, creates)
        gallery.onCommand(player, command, "npcpreview", arrayOf("stop"))
        verify(player).removeResourcePack(first)
        gallery.onCommand(player, command, "npcpreview", emptyArray())
        verify(player, times(2))
            .addResourcePack(
                ids.capture(),
                anyString(),
                any(ByteArray::class.java),
                anyString(),
                eq(false),
            )
        val second = ids.value
        assertNotEquals(first, second)
        gallery.packStatus(
            PlayerResourcePackStatusEvent(
                player,
                first,
                PlayerResourcePackStatusEvent.Status.DISCARDED,
            )
        )
        gallery.packStatus(
            PlayerResourcePackStatusEvent(
                player,
                second,
                PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED,
            )
        )
        gallery.tick()
        assertEquals(10, creates)
        val beforeClose = advances
        gallery.close()
        gallery.tick()
        assertEquals(beforeClose, advances)
        verify(player).removeResourcePack(second)
        verify(player, never()).removeResourcePacks()
    }
}
