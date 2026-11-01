package gg.grounds.scene.editor.paper

import org.bukkit.plugin.java.JavaPlugin

/** JavaPlugin entry point deliberately limited to runtime composition and teardown. */
open class GroundsSceneEditorPlugin : JavaPlugin() {
    private var runtime: PaperSceneEditorRuntime? = null
    private var npcPreview: gg.grounds.scene.editor.paper.modeltest.NpcPreviewGallery? = null

    override fun onEnable() {
        val created = createRuntime()
        try {
            created.register()
            runtime = created
            val preview = gg.grounds.scene.editor.paper.modeltest.NpcPreviewGallery(this)
            npcPreview = preview
            preview.register()
        } catch (error: Throwable) {
            try {
                npcPreview?.close()
                npcPreview = null
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            runtime = null
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
        try {
            npcPreview?.close()
        } finally {
            npcPreview = null
            try {
                runtime?.close()
            } finally {
                runtime = null
            }
        }
    }
}
