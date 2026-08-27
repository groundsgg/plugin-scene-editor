package gg.grounds.scene.editor.paper

import de.eintosti.buildsystem.api.BuildSystem
import de.eintosti.buildsystem.api.storage.WorldStorage
import de.eintosti.buildsystem.api.world.BuildWorld
import de.eintosti.buildsystem.api.world.WorldService
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.Optional
import java.util.UUID
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PaperSessionResolverTest {
    @AfterEach fun tearDown() = MockBukkit.unmock()

    @Test
    fun `default resolver follows the registered BuildSystem service and handles its loss`() {
        val server = MockBukkit.mock()
        val dependency = MockBukkit.createMockPlugin()
        val player = server.addPlayer()
        val buildSystem = mock(BuildSystem::class.java)
        val worldService = mock(WorldService::class.java)
        val worldStorage = mock(WorldStorage::class.java)
        val buildWorld = mock(BuildWorld::class.java)
        `when`(buildSystem.worldService).thenReturn(worldService)
        `when`(worldService.worldStorage).thenReturn(worldStorage)
        `when`(worldStorage.getBuildWorld(player.world)).thenReturn(buildWorld)
        `when`(buildWorld.world).thenReturn(Optional.of(player.world))
        server.servicesManager.register(
            BuildSystem::class.java,
            buildSystem,
            dependency,
            ServicePriority.Normal,
        )
        val resolver = PaperSessionResolver()

        assertEquals(
            BuildWorldTarget(player.world.uid, player.world.worldFolder.toPath()),
            resolver.resolve(player),
        )
        verify(worldStorage).getBuildWorld(player.world)

        server.servicesManager.unregister(BuildSystem::class.java, buildSystem)

        assertNull(resolver.resolve(player))
    }

    @Test
    fun `does not conceal BuildSystem linkage errors`() {
        val resolver = PaperSessionResolver { throw NoClassDefFoundError("BuildSystem") }

        assertThrows(NoClassDefFoundError::class.java) { resolver.resolve(playerIn(world())) }
    }

    @Test
    fun `rejects a world that is not known to BuildSystem`() {
        val world = world()
        val resolver = PaperSessionResolver { null }

        assertNull(resolver.resolve(playerIn(world)))
    }

    @Test
    fun `resolves only the exact loaded BuildSystem world`() {
        val world = world()
        val resolver = PaperSessionResolver { requested -> buildWorld(requested) }

        assertEquals(
            BuildWorldTarget(world.uid, world.worldFolder.toPath()),
            resolver.resolve(playerIn(world)),
        )
    }

    @Test
    fun `rejects a BuildSystem world whose loaded Bukkit world changed`() {
        val current = world()
        val stale = world()
        val resolver = PaperSessionResolver { buildWorld(stale) }

        assertNull(resolver.resolve(playerIn(current)))
    }

    @Test
    fun `treats BuildSystem service loss as an unavailable build world`() {
        val resolver = PaperSessionResolver {
            throw IllegalStateException("BuildSystem has disabled")
        }

        assertNull(resolver.resolve(playerIn(world())))
    }

    private fun world(): World {
        val id = UUID.randomUUID()
        val folder = Path.of("/tmp", "scene-resolver-$id").toFile()
        return proxy { method, _ ->
            when (method.name) {
                "getUID" -> id
                "getWorldFolder" -> folder
                "toString" -> "test-world"
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun playerIn(world: World): Player = proxy { method, _ ->
        when (method.name) {
            "getWorld" -> world
            "toString" -> "scene-editor-test-player"
            else -> defaultValue(method.returnType)
        }
    }

    private fun buildWorld(world: World): BuildWorld = proxy { method, _ ->
        when (method.name) {
            "getWorld" -> Optional.of(world)
            "toString" -> "scene-editor-test-build-world"
            else -> defaultValue(method.returnType)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(
        crossinline handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?
    ): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args
            ->
            handler(method, args)
        } as T

    private fun defaultValue(type: Class<*>): Any? =
        when {
            !type.isPrimitive -> null
            type == Boolean::class.javaPrimitiveType -> false
            type == Char::class.javaPrimitiveType -> '\u0000'
            type == Byte::class.javaPrimitiveType -> 0.toByte()
            type == Short::class.javaPrimitiveType -> 0.toShort()
            type == Int::class.javaPrimitiveType -> 0
            type == Long::class.javaPrimitiveType -> 0L
            type == Float::class.javaPrimitiveType -> 0F
            type == Double::class.javaPrimitiveType -> 0.0
            else -> error("Unhandled primitive $type")
        }
}
