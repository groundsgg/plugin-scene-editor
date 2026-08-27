package gg.grounds.scene.editor.paper

import de.eintosti.buildsystem.api.BuildSystem
import gg.grounds.scene.editor.SceneEditStatus
import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.paper.command.SceneCommand
import gg.grounds.scene.editor.paper.command.SceneTabCompleter
import gg.grounds.scene.editor.paper.preview.BukkitPreviewEntityFactory
import gg.grounds.scene.editor.paper.preview.PaperPreviewAdapter
import gg.grounds.scene.editor.paper.preview.PaperPreviewController
import gg.grounds.scene.editor.paper.tool.EditorTool
import gg.grounds.scene.editor.paper.tool.EditorToolListener
import gg.grounds.scene.editor.session.EditorSessionService
import org.bukkit.event.HandlerList
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/** Runtime composition with explicit ownership; the JavaPlugin remains a thin entry point. */
class PaperSceneEditorRuntime(
    private val plugin: JavaPlugin,
    catalogFactory: () -> SceneCatalogBinding = SceneCatalogBinding::production,
    schedulerFactory: (JavaPlugin) -> PaperScheduler = ::PaperScheduler,
    previewFactory: (JavaPlugin) -> PaperPreviewAdapter = {
        PaperPreviewAdapter(BukkitPreviewEntityFactory(it))
    },
    private val commandRegistrar: (SceneCommand, SceneTabCompleter) -> Unit =
        { command, completer ->
            plugin.getCommand("scene")?.let {
                it.setExecutor(command)
                it.tabCompleter = completer
            } ?: error("plugin.yml must declare /scene")
        },
) : AutoCloseable {
    val catalogs = catalogFactory()
    val sessions =
        EditorSessionService(catalogs) { error ->
            plugin.logger.warning("Scene editor event failed: ${error.message}")
        }
    private val scheduler = schedulerFactory(plugin)
    private val resolver = PaperSessionResolver()
    private val previews = previewFactory(plugin)
    private val previewController = PaperPreviewController(plugin, sessions, resolver, previews)
    private val editorTool = EditorTool(plugin)
    private val toolListener = EditorToolListener(editorTool, sessions, catalogs, resolver)
    private val playerLifecycle =
        PaperPlayerLifecycleListener(sessions, previews, toolListener::clearPlayer)
    private val worldLifecycle = PaperWorldLifecycleListener(plugin, sessions, previews)
    private val command =
        SceneCommand(
            plugin,
            sessions,
            catalogs,
            resolver,
            scheduler,
            AdventureSceneFeedback(),
            editorTool,
        )

    fun register() {
        check(plugin.server.servicesManager.getRegistration(BuildSystem::class.java) != null) {
            "BuildSystem service is unavailable despite the hard plugin dependency"
        }
        val services = plugin.server.servicesManager
        services.register(SceneEditStatus::class.java, sessions, plugin, ServicePriority.Normal)
        try {
            plugin.server.pluginManager.registerEvents(playerLifecycle, plugin)
            plugin.server.pluginManager.registerEvents(worldLifecycle, plugin)
            plugin.server.pluginManager.registerEvents(toolListener, plugin)
            previewController.start()
            // Keep command registration last: it has no generic undo seam, so no later step may
            // fail.
            commandRegistrar(command, SceneTabCompleter(command))
        } catch (error: Throwable) {
            previewController.close()
            HandlerList.unregisterAll(playerLifecycle)
            HandlerList.unregisterAll(worldLifecycle)
            HandlerList.unregisterAll(toolListener)
            previews.close()
            services.unregisterAll(plugin)
            throw error
        }
    }

    override fun close() {
        var failure: Throwable? = null
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
        }
        try {
            attempt(previewController::close)
            HandlerList.unregisterAll(playerLifecycle)
            HandlerList.unregisterAll(worldLifecycle)
            HandlerList.unregisterAll(toolListener)
            attempt(worldLifecycle::closeAll)
            attempt(previews::close)
            attempt(scheduler::close)
        } finally {
            plugin.server.servicesManager.unregisterAll(plugin)
        }
        failure?.let { throw it }
    }
}
