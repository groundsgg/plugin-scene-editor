package gg.grounds.scene.editor.paper.preview

import gg.grounds.resourcepacks.npc.*
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.Vec3
import java.util.UUID
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.SoundCategory
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.joml.Matrix4f
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
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

    @Test
    fun `model parts use item models advance poses and play greeting once for their viewer`() {
        val plugin = MockBukkit.createMockPlugin()
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID(1, 1))
        val viewer = mock(Player::class.java)
        `when`(viewer.world).thenReturn(world)
        `when`(viewer.isOnline).thenReturn(true)
        `when`(viewer.location).thenReturn(Location(world, 1.0, 2.0, 3.0))
        val asset = AssetKey("grounds:npc/test")
        val model =
            NpcModel(
                asset,
                LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                listOf(NpcBone("root", null, Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 0.0))),
                listOf(
                    NpcPart(
                        "root",
                        "grounds:npc_parts/test",
                        Vec3(1.0, 0.0, 0.0),
                        Vec3(0.0, 0.0, 0.0),
                        Vec3(0.0, 0.0, 0.0),
                    )
                ),
                mapOf(
                    "idle" to NpcClip("loop", 2.0, emptyMap()),
                    "wave" to
                        NpcClip(
                            "once",
                            1.0,
                            mapOf(
                                "root" to
                                    mapOf(
                                        "position" to
                                            listOf(
                                                NpcKeyframe(0.0, Vec3(0.0, 0.0, 0.0), "linear"),
                                                NpcKeyframe(1.0, Vec3(2.0, 0.0, 0.0), "linear"),
                                            )
                                    )
                            ),
                        ),
                ),
                mapOf("wave" to NpcClipSound("grounds:npc/test", 0.8, 1.0, 12.0)),
            )
        val spawner = RecordingSpawner()
        var now = 0L
        val handle =
            BukkitPreviewEntityFactory(plugin, plugin.logger, spawner, mapOf(asset to model)) {
                    now
                }
                .create(
                    viewer,
                    descriptor()
                        .copy(
                            asset = asset.value,
                            initialAnimation = "idle",
                            enterRadius = 4.0,
                            exitRadius = 5.0,
                            greetingAnimation = "wave",
                        ),
                )
        val display = spawner.entities.single() as ItemDisplay
        val item = ArgumentCaptor.forClass(ItemStack::class.java)
        verify(display).setItemStack(item.capture())
        assertEquals("grounds:npc_parts/test", item.value.itemMeta.itemModel.toString())
        verify(display).isVisibleByDefault = false
        verify(viewer).showEntity(plugin, display)
        handle.highlight(true)
        handle.highlight(true)
        verify(display, times(1)).glowColorOverride = org.bukkit.Color.AQUA
        verify(display, times(1)).isGlowing = true
        handle.highlight(false)
        verify(display).glowColorOverride = null
        verify(display).isGlowing = false
        assertEquals(1, spawner.entities.size)
        handle.advance()
        now = 500
        handle.advance()
        val matrices = ArgumentCaptor.forClass(Matrix4f::class.java)
        verify(display, times(2)).setTransformationMatrix(matrices.capture())
        assertEquals(-1.0f, matrices.allValues.first().m30(), 0.0001f)
        assertEquals(-2.0f, matrices.value.m30(), 0.0001f)
        verify(viewer, times(1))
            .playSound(
                org.mockito.ArgumentMatchers.any(Location::class.java),
                org.mockito.ArgumentMatchers.eq("grounds:npc/test"),
                org.mockito.ArgumentMatchers.eq(SoundCategory.VOICE),
                org.mockito.ArgumentMatchers.eq(0.8f),
                org.mockito.ArgumentMatchers.eq(1.0f),
            )
        handle.remove()
        verify(display).remove()
    }

    @Test
    fun `stationary parts finish interpolation without repeating start-only metadata`() {
        val plugin = MockBukkit.createMockPlugin()
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID(1, 1))
        val viewer = mock(Player::class.java)
        `when`(viewer.world).thenReturn(world)
        `when`(viewer.isOnline).thenReturn(true)
        `when`(viewer.location).thenReturn(Location(world, 1.0, 2.0, 3.0))
        val asset = AssetKey("grounds:npc/test/anvil")
        val model =
            NpcModel(
                asset,
                LocalBounds(Vec3(0.0, 1.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                emptyList(),
                listOf(
                    NpcPart(
                        null,
                        "grounds:npc_parts/test/anvil",
                        Vec3(0.0, 0.75, -0.5),
                        Vec3(0.0, 0.0, -0.5),
                        Vec3(0.0, 0.0, 0.0),
                    )
                ),
                mapOf("idle" to NpcClip("loop", 4.0, emptyMap())),
                emptyMap(),
            )
        val spawner = RecordingSpawner()
        var now = 0L
        val handle =
            BukkitPreviewEntityFactory(plugin, plugin.logger, spawner, mapOf(asset to model)) {
                    now
                }
                .create(viewer, descriptor().copy(asset = asset.value, initialAnimation = "idle"))
        for (time in listOf(0L, 100L, 200L, 300L, 400L)) {
            now = time
            handle.advance()
        }
        val display = spawner.entities.single() as ItemDisplay
        verify(display, times(1))
            .setTransformationMatrix(org.mockito.ArgumentMatchers.any(Matrix4f::class.java))
        // Paper forces this metadata even when it is already zero. Sending it without a new
        // transform restarts the old client interpolator, making static legs/anvils loop forever.
        verify(display, times(1)).interpolationDelay = 0
        handle.remove()
    }

    @Test
    fun `completed manual one shot returns to idle without replaying sound`() {
        val plugin = MockBukkit.createMockPlugin()
        val world = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID(1, 1))
        val viewer = mock(Player::class.java)
        `when`(viewer.world).thenReturn(world)
        `when`(viewer.isOnline).thenReturn(true)
        `when`(viewer.location).thenReturn(Location(world, 1.0, 2.0, 3.0))
        val asset = AssetKey("grounds:npc/test")
        val model =
            NpcModel(
                asset,
                LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                emptyList(),
                listOf(
                    NpcPart(
                        null,
                        "grounds:npc_parts/test",
                        Vec3(0.0, 0.0, 0.0),
                        Vec3(0.0, 0.0, 0.0),
                        Vec3(0.0, 0.0, 0.0),
                    )
                ),
                mapOf(
                    "idle" to NpcClip("loop", 2.0, emptyMap()),
                    "wave" to NpcClip("once", 1.0, emptyMap()),
                    "idle2" to NpcClip("loop", 1.0, emptyMap()),
                ),
                mapOf(
                    "wave" to NpcClipSound("grounds:npc/test", 0.8, 1.0, 12.0),
                    "idle2" to NpcClipSound("grounds:npc/test", 0.8, 1.0, 12.0),
                ),
            )
        for (initial in listOf("wave", "idle2")) {
            org.mockito.Mockito.clearInvocations(viewer)
            var now = 0L
            val handle =
                BukkitPreviewEntityFactory(
                        plugin,
                        plugin.logger,
                        RecordingSpawner(),
                        mapOf(asset to model),
                    ) {
                        now
                    }
                    .create(
                        viewer,
                        descriptor().copy(asset = asset.value, initialAnimation = initial),
                    )
            handle.advance()
            now = 1100L
            handle.advance()
            now = 2200L
            handle.advance()
            verify(viewer, times(1))
                .playSound(
                    org.mockito.ArgumentMatchers.any(Location::class.java),
                    org.mockito.ArgumentMatchers.eq("grounds:npc/test"),
                    org.mockito.ArgumentMatchers.eq(SoundCategory.VOICE),
                    org.mockito.ArgumentMatchers.eq(0.8f),
                    org.mockito.ArgumentMatchers.eq(1.0f),
                )
            handle.remove()
        }
    }

    @Test
    fun `world switch and disconnect prevent model updates and sounds`() {
        val plugin = MockBukkit.createMockPlugin()
        val world = mock(World::class.java)
        val other = mock(World::class.java)
        `when`(world.uid).thenReturn(UUID(1, 1))
        `when`(other.uid).thenReturn(UUID(2, 2))
        val viewer = mock(Player::class.java)
        `when`(viewer.world).thenReturn(world)
        `when`(viewer.isOnline).thenReturn(true)
        `when`(viewer.location).thenReturn(Location(world, 1.0, 2.0, 3.0))
        val asset = AssetKey("grounds:npc/test")
        val model =
            NpcModel(
                asset,
                LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                emptyList(),
                listOf(
                    NpcPart(
                        null,
                        "grounds:npc_parts/test",
                        Vec3(0.0, 0.0, 0.0),
                        Vec3(0.0, 0.0, 0.0),
                        Vec3(0.0, 0.0, 0.0),
                    )
                ),
                mapOf("wave" to NpcClip("once", 1.0, emptyMap())),
                mapOf("wave" to NpcClipSound("grounds:npc/test", 0.8, 1.0, 12.0)),
            )
        val spawner = RecordingSpawner()
        val handle =
            BukkitPreviewEntityFactory(plugin, plugin.logger, spawner, mapOf(asset to model))
                .create(viewer, descriptor().copy(asset = asset.value, initialAnimation = "wave"))
        val display = spawner.entities.single() as ItemDisplay
        `when`(viewer.world).thenReturn(other)
        `when`(viewer.location).thenReturn(Location(other, 1.0, 2.0, 3.0))
        handle.advance()
        `when`(viewer.world).thenReturn(world)
        `when`(viewer.isOnline).thenReturn(false)
        handle.advance()
        verify(display, org.mockito.Mockito.never())
            .setTransformationMatrix(org.mockito.ArgumentMatchers.any(Matrix4f::class.java))
        verify(viewer, org.mockito.Mockito.never())
            .playSound(
                org.mockito.ArgumentMatchers.any(Location::class.java),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(SoundCategory::class.java),
                org.mockito.ArgumentMatchers.anyFloat(),
                org.mockito.ArgumentMatchers.anyFloat(),
            )
    }

    @Test
    fun `removed handles do not advance and remove entities only once`() {
        val entity = mock(Entity::class.java)
        var advances = 0
        val handle = PreviewHandle(listOf(entity)) { advances++ }
        handle.advance()
        handle.remove()
        handle.remove()
        handle.advance()
        assertEquals(1, advances)
        verify(entity, times(1)).remove()
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
