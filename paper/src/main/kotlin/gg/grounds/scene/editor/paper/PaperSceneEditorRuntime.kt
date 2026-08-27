package gg.grounds.scene.editor.paper

import de.eintosti.buildsystem.api.BuildSystem
import gg.grounds.scene.editor.SceneEditStatus
import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.paper.command.SceneCommand
import gg.grounds.scene.editor.paper.command.SceneTabCompleter
import gg.grounds.scene.editor.session.EditorSessionService
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/** Runtime composition with explicit ownership; the JavaPlugin remains a thin entry point. */
class PaperSceneEditorRuntime(
    private val plugin: JavaPlugin,
    catalogFactory: () -> SceneCatalogBinding = SceneCatalogBinding::production,
    schedulerFactory: (JavaPlugin) -> PaperScheduler = ::PaperScheduler,
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
    private val command =
        SceneCommand(
            plugin,
            sessions,
            catalogs,
            PaperSessionResolver(),
            scheduler,
            AdventureSceneFeedback(),
        )

    fun register() {
        check(plugin.server.servicesManager.getRegistration(BuildSystem::class.java) != null) {
            "BuildSystem service is unavailable despite the hard plugin dependency"
        }
        val services = plugin.server.servicesManager
        services.register(SceneEditStatus::class.java, sessions, plugin, ServicePriority.Normal)
        try {
            commandRegistrar(command, SceneTabCompleter(command))
        } catch (error: Throwable) {
            services.unregisterAll(plugin)
            throw error
        }
    }

    override fun close() {
        try {
            scheduler.close()
        } finally {
            plugin.server.servicesManager.unregisterAll(plugin)
        }
    }
}
