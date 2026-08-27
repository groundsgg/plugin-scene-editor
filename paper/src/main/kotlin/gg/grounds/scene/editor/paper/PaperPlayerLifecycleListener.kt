package gg.grounds.scene.editor.paper

import gg.grounds.scene.editor.paper.preview.PaperPreviewAdapter
import gg.grounds.scene.editor.session.EditorSessionService
import java.util.UUID
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent

class PaperPlayerLifecycleListener(
    private val sessions: EditorSessionService,
    private val previews: PaperPreviewAdapter,
    private val clearToolState: (UUID) -> Unit = {},
) : Listener {
    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        sessions.openWorldIds().forEach { worldId -> sessions.releasePlayer(worldId, playerId) }
        previews.clearViewer(playerId)
        clearToolState(playerId)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        sessions.releasePlayer(event.from.uid, event.player.uniqueId)
        previews.clearViewer(event.from.uid, event.player.uniqueId)
        clearToolState(event.player.uniqueId)
    }
}
