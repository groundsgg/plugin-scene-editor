package gg.grounds.scene.editor.paper

import gg.grounds.scene.editor.paper.command.SceneCommandScheduler
import gg.grounds.scene.editor.paper.command.SceneWorldResolver
import gg.grounds.scene.editor.repository.SceneLoadResult
import gg.grounds.scene.editor.repository.WorldSceneRepository
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.editor.session.SessionBootstrapResult
import java.util.UUID
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class PaperAutomaticSceneLoader(
    private val plugin: JavaPlugin,
    private val sessions: EditorSessionService,
    private val resolver: SceneWorldResolver,
    private val scheduler: SceneCommandScheduler,
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val pending = mutableMapOf<UUID, Request>()
    private var closed = false

    fun openFor(player: Player) {
        val world = player.world
        val target = resolver.resolve(player) ?: return
        if (target.worldId != world.uid || sessions.session(target.worldId) != null) return
        val request = Request(world, target)
        synchronized(lifecycleLock) {
            if (closed || pending.containsKey(target.worldId)) return
            if (sessions.session(target.worldId) != null) return
            pending[target.worldId] = request
        }
        val repository = WorldSceneRepository(target.worldFolder)
        try {
            scheduler.asyncThenMain(
                task = repository::load,
                failure = { error -> fail(request, error) },
                apply = { load -> apply(request, load) },
            )
        } catch (error: Throwable) {
            fail(request, error)
        }
    }

    fun invalidate(world: World) {
        synchronized(lifecycleLock) {
            if (pending[world.uid]?.world === world) pending.remove(world.uid)
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            closed = true
            pending.clear()
        }
    }

    private fun apply(request: Request, load: SceneLoadResult) {
        val current =
            synchronized(lifecycleLock) {
                if (closed || pending[request.target.worldId] !== request) false
                else {
                    pending.remove(request.target.worldId)
                    true
                }
            }
        if (!current || plugin.server.getWorld(request.target.worldId) !== request.world) return
        when (val result = sessions.openFromLoad(request.target.worldId, load)) {
            is SessionBootstrapResult.InvalidSource ->
                warn(request.target.worldId, "invalid-source")
            is SessionBootstrapResult.RejectedSource ->
                warn(request.target.worldId, "rejected-source:${result.load.reason}")
            SessionBootstrapResult.EncodingFailure ->
                warn(request.target.worldId, "encoding-failure")
            else -> Unit
        }
    }

    private fun fail(request: Request, error: Throwable) {
        val current =
            synchronized(lifecycleLock) {
                if (pending[request.target.worldId] !== request) false
                else {
                    pending.remove(request.target.worldId)
                    !closed
                }
            }
        if (current)
            warn(
                request.target.worldId,
                "load-failure:${error::class.simpleName ?: "Throwable"}",
            )
    }

    private fun warn(worldId: UUID, classification: String) {
        plugin.logger.warning("Automatic scene open failed for world $worldId ($classification)")
    }

    private data class Request(val world: World, val target: BuildWorldTarget)
}
