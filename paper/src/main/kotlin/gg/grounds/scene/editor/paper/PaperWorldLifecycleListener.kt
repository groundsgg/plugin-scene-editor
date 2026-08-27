package gg.grounds.scene.editor.paper

import gg.grounds.scene.editor.paper.preview.PaperPreviewAdapter
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.editor.session.SessionCloseResult
import gg.grounds.scene.editor.session.SessionCloseSnapshot
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.java.JavaPlugin

class PaperWorldLifecycleListener(
    private val plugin: JavaPlugin,
    private val sessions: EditorSessionService,
    private val previews: PaperPreviewAdapter,
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        val worldId = event.world.uid
        val closed = sessions.closeWorld(worldId)
        previews.clearWorld(worldId)
        if (closed is SessionCloseResult.Closed) report(plugin.server, closed.snapshot)
    }

    fun closeAll() {
        sessions.closeAll().forEach { snapshot -> report(plugin.server, snapshot) }
    }

    private fun report(server: Server, snapshot: SessionCloseSnapshot) {
        if (snapshot.dirty) {
            plugin.logger.warning(
                "Closing dirty scene editor session for world ${snapshot.worldId} at generation ${snapshot.generation}"
            )
            snapshot.editorPlayers.forEach { playerId ->
                server
                    .getPlayer(playerId)
                    ?.sendMessage(Component.text("Scene editor closed with unsaved changes."))
            }
        }
    }
}
