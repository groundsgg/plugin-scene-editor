package gg.grounds.scene.editor.paper

import org.bukkit.plugin.java.JavaPlugin

/** JavaPlugin entry point deliberately limited to runtime composition and teardown. */
open class GroundsSceneEditorPlugin : JavaPlugin() {
    private var runtime: PaperSceneEditorRuntime? = null

    override fun onEnable() {
        val created = createRuntime()
        try {
            created.register()
            runtime = created
        } catch (error: Throwable) {
            try {
                created.close()
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    protected open fun createRuntime(): PaperSceneEditorRuntime = PaperSceneEditorRuntime(this)

    override fun onDisable() {
        runtime?.close()
        runtime = null
    }
}
