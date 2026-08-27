package gg.grounds.scene.editor.paper

import de.eintosti.buildsystem.api.BuildSystem
import gg.grounds.scene.editor.SceneEditStatus
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import java.lang.reflect.Proxy
import java.util.function.Consumer
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PaperSceneEditorRuntimeTest {
    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `fails explicitly when BuildSystem service is absent`() {
        MockBukkit.mock()
        val plugin = MockBukkit.createMockPlugin()

        val failure =
            assertThrows(
                IllegalStateException::class.java,
                Executable { PaperSceneEditorRuntime(plugin).register() },
            )

        assertTrue(failure.message.orEmpty().contains("BuildSystem service is unavailable"))
        assertNull(plugin.server.servicesManager.getRegistration(SceneEditStatus::class.java))
    }

    @Test
    fun `propagates catalog initialization failures before registering partial runtime state`() {
        MockBukkit.mock()
        val plugin = MockBukkit.createMockPlugin()

        val failure =
            assertThrows(
                IllegalStateException::class.java,
                Executable {
                    PaperSceneEditorRuntime(
                        plugin,
                        catalogFactory = { throw IllegalStateException("catalog unavailable") },
                    )
                },
            )

        assertEquals("catalog unavailable", failure.message)
        assertNull(plugin.server.servicesManager.getRegistration(SceneEditStatus::class.java))
    }

    @Test
    fun `uses independent runtime session services rather than a mutable global singleton`() {
        MockBukkit.mock()
        val first = PaperSceneEditorRuntime(MockBukkit.createMockPlugin())
        val second = PaperSceneEditorRuntime(MockBukkit.createMockPlugin())

        assertNotSame(first.sessions, second.sessions)
    }

    @Test
    fun `unregisters the scene status service when runtime closes`() {
        val server = MockBukkit.mock()
        val dependency = MockBukkit.createMockPlugin()
        server.servicesManager.register(
            BuildSystem::class.java,
            buildSystem(),
            dependency,
            ServicePriority.Normal,
        )
        val plugin = MockBukkit.load(GroundsSceneEditorPlugin::class.java)

        assertNotNull(plugin.server.servicesManager.getRegistration(SceneEditStatus::class.java))
        val scene = requireNotNull(plugin.getCommand("scene"))
        assertNotNull(scene.executor)
        assertNotNull(scene.tabCompleter)

        plugin.onDisable()

        assertNull(plugin.server.servicesManager.getRegistration(SceneEditStatus::class.java))
    }

    @Test
    fun `closes scheduler before unregistering scene status`() {
        val server = MockBukkit.mock()
        val dependency = MockBukkit.createMockPlugin()
        server.servicesManager.register(
            BuildSystem::class.java,
            buildSystem(),
            dependency,
            ServicePriority.Normal,
        )
        val plugin = MockBukkit.createMockPlugin()
        val closeObservedWithRegisteredService = mutableListOf<Boolean>()
        val runtime =
            PaperSceneEditorRuntime(
                plugin,
                schedulerFactory = {
                    object : PaperScheduler(plugin) {
                        override fun close() {
                            closeObservedWithRegisteredService +=
                                plugin.server.servicesManager.getRegistration(
                                    SceneEditStatus::class.java
                                ) != null
                        }
                    }
                },
                commandRegistrar = { _, _ -> },
            )

        runtime.register()
        runtime.close()

        assertEquals(listOf(true), closeObservedWithRegisteredService)
        assertNull(plugin.server.servicesManager.getRegistration(SceneEditStatus::class.java))
    }

    @Test
    fun `unregisters scene status when scheduler close fails`() {
        val server = MockBukkit.mock()
        val dependency = MockBukkit.createMockPlugin()
        server.servicesManager.register(
            BuildSystem::class.java,
            buildSystem(),
            dependency,
            ServicePriority.Normal,
        )
        val plugin = MockBukkit.createMockPlugin()
        val runtime =
            PaperSceneEditorRuntime(
                plugin,
                schedulerFactory = {
                    object : PaperScheduler(plugin) {
                        override fun close() = throw IllegalStateException("scheduler close failed")
                    }
                },
                commandRegistrar = { _, _ -> },
            )
        runtime.register()

        val failure =
            assertThrows(IllegalStateException::class.java, Executable { runtime.close() })

        assertEquals("scheduler close failed", failure.message)
        assertNull(plugin.server.servicesManager.getRegistration(SceneEditStatus::class.java))
    }

    @Test
    fun `rolls back the scene status service when command registration fails`() {
        val server = MockBukkit.mock()
        val dependency = MockBukkit.createMockPlugin()
        server.servicesManager.register(
            BuildSystem::class.java,
            buildSystem(),
            dependency,
            ServicePriority.Normal,
        )
        val plugin = MockBukkit.createMockPlugin()
        val runtime =
            PaperSceneEditorRuntime(
                plugin,
                commandRegistrar = { _, _ -> error("command registration failed") },
            )

        assertThrows(IllegalStateException::class.java, Executable { runtime.register() })

        assertNull(plugin.server.servicesManager.getRegistration(SceneEditStatus::class.java))
    }

    @Test
    fun `enable preserves the registration failure when cleanup also fails`() {
        val server = MockBukkit.mock()
        val dependency = MockBukkit.createMockPlugin()
        server.servicesManager.register(
            BuildSystem::class.java,
            buildSystem(),
            dependency,
            ServicePriority.Normal,
        )

        val failure =
            assertThrows(
                IllegalStateException::class.java,
                Executable { MockBukkit.load(CloseFailingEnablePlugin::class.java) },
            )

        assertEquals("registration failed", failure.message)
        assertEquals(listOf("cleanup failed"), failure.suppressedExceptions.map { it.message })
    }

    @Test
    fun `scheduler cancellation closes both owned Paper scheduler domains`() {
        val plugin = mock(org.bukkit.plugin.java.JavaPlugin::class.java)
        val server = mock(org.bukkit.Server::class.java)
        val bukkitScheduler = mock(org.bukkit.scheduler.BukkitScheduler::class.java)
        val asyncScheduler =
            mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(bukkitScheduler)
        `when`(server.asyncScheduler).thenReturn(asyncScheduler)

        PaperScheduler(plugin).close()

        verify(bukkitScheduler).cancelTasks(plugin)
        verify(asyncScheduler).cancelTasks(plugin)
    }

    @Test
    fun `close prevents a running async task from scheduling a main callback`() {
        val plugin = mock(org.bukkit.plugin.java.JavaPlugin::class.java)
        val server = mock(org.bukkit.Server::class.java)
        val bukkitScheduler = mock(org.bukkit.scheduler.BukkitScheduler::class.java)
        val asyncScheduler =
            mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java)
        var queued: Consumer<ScheduledTask>? = null
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(bukkitScheduler)
        `when`(server.asyncScheduler).thenReturn(asyncScheduler)
        `when`(
                asyncScheduler.runNow(
                    org.mockito.ArgumentMatchers.eq(plugin),
                    org.mockito.ArgumentMatchers.any(),
                )
            )
            .thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                queued = invocation.getArgument<Consumer<ScheduledTask>>(1)
                mock(ScheduledTask::class.java)
            }
        val scheduler = PaperScheduler(plugin)

        scheduler.asyncThenMain({ "loaded" }) { error("unexpected failure: $it") }
        scheduler.close()
        requireNotNull(queued).accept(mock(ScheduledTask::class.java))

        verify(bukkitScheduler, never())
            .runTask(
                org.mockito.ArgumentMatchers.eq(plugin),
                org.mockito.ArgumentMatchers.any(Runnable::class.java),
            )
    }

    @Test
    fun `scheduler rejects new work after close`() {
        val plugin = mock(org.bukkit.plugin.java.JavaPlugin::class.java)
        val server = mock(org.bukkit.Server::class.java)
        val bukkitScheduler = mock(org.bukkit.scheduler.BukkitScheduler::class.java)
        val asyncScheduler =
            mock(io.papermc.paper.threadedregions.scheduler.AsyncScheduler::class.java)
        `when`(plugin.server).thenReturn(server)
        `when`(server.scheduler).thenReturn(bukkitScheduler)
        `when`(server.asyncScheduler).thenReturn(asyncScheduler)
        val scheduler = PaperScheduler(plugin)
        scheduler.close()

        val future = scheduler.asyncThenMain({ "loaded" }) { error("unexpected failure: $it") }

        assertTrue(future.isCompletedExceptionally)
        verify(asyncScheduler, never())
            .runNow(org.mockito.ArgumentMatchers.eq(plugin), org.mockito.ArgumentMatchers.any())
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildSystem(): BuildSystem =
        Proxy.newProxyInstance(
            BuildSystem::class.java.classLoader,
            arrayOf(BuildSystem::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "toString" -> "test-buildsystem"
                else -> null
            }
        } as BuildSystem
}

open class CloseFailingEnablePlugin : GroundsSceneEditorPlugin() {
    override fun createRuntime(): PaperSceneEditorRuntime =
        PaperSceneEditorRuntime(
            this,
            schedulerFactory = {
                object : PaperScheduler(this@CloseFailingEnablePlugin) {
                    override fun close() = throw IllegalStateException("cleanup failed")
                }
            },
            commandRegistrar = { _, _ -> error("registration failed") },
        )
}
