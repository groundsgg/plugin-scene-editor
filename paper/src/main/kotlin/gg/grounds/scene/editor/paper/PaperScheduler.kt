package gg.grounds.scene.editor.paper

import gg.grounds.scene.editor.paper.command.SceneCommandScheduler
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import org.bukkit.plugin.java.JavaPlugin

/** Separates asynchronous disk work from main-thread Bukkit application. */
open class PaperScheduler(private val plugin: JavaPlugin) : SceneCommandScheduler, AutoCloseable {
    private val lifecycleLock = Any()
    private var closed = false
    private val async: Executor = Executor { task ->
        plugin.server.asyncScheduler.runNow(plugin) { task.run() }
    }

    override fun <T> asyncThenMain(
        task: () -> T,
        failure: (Throwable) -> Unit,
        apply: (T) -> Unit,
    ): CompletableFuture<T> =
        synchronized(lifecycleLock) {
            if (closed)
                return@synchronized CompletableFuture.failedFuture(
                    IllegalStateException("Scene scheduler is closed")
                )
            CompletableFuture.supplyAsync(task, async).whenComplete { value, error ->
                synchronized(lifecycleLock) {
                    if (!closed)
                        plugin.server.scheduler.runTask(
                            plugin,
                            Runnable {
                                synchronized(lifecycleLock) {
                                    if (!closed) {
                                        if (error == null) apply(value)
                                        else failure(error.cause ?: error)
                                    }
                                }
                            },
                        )
                }
            }
        }

    fun <T> asyncThenMain(task: () -> T, apply: (T) -> Unit): CompletableFuture<T> =
        asyncThenMain(
            task,
            { error ->
                plugin.logger.warning("Scene editor async operation failed: ${error.message}")
            },
            apply,
        )

    open override fun close() {
        val cancelTasks =
            synchronized(lifecycleLock) {
                if (closed) false
                else {
                    closed = true
                    true
                }
            }
        if (!cancelTasks) return
        plugin.server.scheduler.cancelTasks(plugin)
        plugin.server.asyncScheduler.cancelTasks(plugin)
    }
}
