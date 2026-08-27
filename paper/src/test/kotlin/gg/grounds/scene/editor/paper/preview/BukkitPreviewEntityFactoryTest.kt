package gg.grounds.scene.editor.paper.preview

import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import java.util.UUID
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataContainer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class BukkitPreviewEntityFactoryTest {
    @BeforeEach
    fun setUp() {
        MockBukkit.mock()
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `selected npc renders body outline axes label and id as tagged viewer-only entities`() {
        val plugin = MockBukkit.createMockPlugin()
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID(1, 1))
        val viewer = mock(Player::class.java)
        `when`(viewer.world).thenReturn(world)
        val spawner = RecordingSpawner()
        val factory = BukkitPreviewEntityFactory(plugin, plugin.logger, spawner)

        val handle =
            factory.create(viewer, descriptor(selected = true, label = Component.text("Guide")))

        assertEquals(5, spawner.entities.count { it is BlockDisplay })
        assertEquals(2, spawner.entities.count { it is TextDisplay })
        spawner.entities.forEach { entity ->
            verify(entity).isPersistent = false
            verify(entity).isVisibleByDefault = false
            verify(viewer).showEntity(plugin, entity)
            assertEquals(
                4,
                mockingDetails(entity.persistentDataContainer).invocations.count {
                    it.method.name == "set"
                },
            )
        }

        handle.remove()
        spawner.entities.forEach { verify(it).remove() }
    }

    @Test
    fun `body rendering failure still creates a visible tagged text fallback`() {
        val plugin = MockBukkit.createMockPlugin()
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID(1, 1))
        val viewer = mock(Player::class.java)
        `when`(viewer.world).thenReturn(world)
        val spawner = RecordingSpawner(failFirstBlock = true)

        val handle =
            BukkitPreviewEntityFactory(plugin, plugin.logger, spawner).create(viewer, descriptor())

        assertEquals(1, spawner.entities.size)
        assertTrue(spawner.entities.single() is TextDisplay)
        verify(viewer).showEntity(plugin, spawner.entities.single())
        handle.remove()
    }

    private fun descriptor(selected: Boolean = false, label: Component? = null) =
        PreviewDescriptor(
            "guide",
            PreviewKind.NPC,
            Transform(Vec3(1.0, 2.0, 3.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
            "grounds:editor/guide",
            label,
            selected,
        )

    private class RecordingSpawner(private val failFirstBlock: Boolean = false) :
        PreviewEntitySpawner {
        val entities = mutableListOf<Entity>()
        private var failed = false

        override fun <T : Entity> spawn(
            world: World,
            location: Location,
            type: Class<T>,
            configure: (T) -> Unit,
        ): T {
            if (failFirstBlock && !failed && type == BlockDisplay::class.java) {
                failed = true
                throw IllegalStateException("renderer unavailable")
            }
            val entity = mock(type)
            `when`(entity.persistentDataContainer)
                .thenReturn(mock(PersistentDataContainer::class.java))
            configure(entity)
            entities += entity
            return entity
        }
    }
}
