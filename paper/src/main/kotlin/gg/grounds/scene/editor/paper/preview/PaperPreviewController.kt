package gg.grounds.scene.editor.paper.preview

import gg.grounds.scene.editor.paper.PaperSessionResolver
import gg.grounds.scene.editor.session.EditorSessionService
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

/** Reconciles immutable snapshots on the server thread; no document is ever mutated here. */
class PaperPreviewController(
    private val plugin: JavaPlugin,
    private val sessions: EditorSessionService,
    private val resolver: PaperSessionResolver,
    private val previews: PaperPreviewAdapter,
) : AutoCloseable {
    private var task: BukkitTask? = null

    fun start() {
        check(task == null) { "Preview controller is already running" }
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::tick), 1L, 5L)
    }

    internal fun tick() {
        plugin.server.onlinePlayers.forEach { player ->
            val target = resolver.resolve(player)
            val snapshot = target?.let { sessions.previewSnapshot(it.worldId) }
            if (snapshot == null) previews.clearViewer(player.uniqueId)
            else
                try {
                    previews.reconcile(player, snapshot)
                } catch (failure: RuntimeException) {
                    plugin.logger.warning(
                        "Could not reconcile scene preview for ${player.name}: ${failure.message}"
                    )
                }
        }
    }

    override fun close() {
        task?.cancel()
        task = null
    }
}
