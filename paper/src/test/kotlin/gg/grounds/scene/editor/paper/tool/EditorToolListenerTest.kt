package gg.grounds.scene.editor.paper.tool

import de.eintosti.buildsystem.api.world.BuildWorld
import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.editor.paper.PaperSessionResolver
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.editor.tool.TransformComponent
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.Vec3
import java.util.Optional
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class EditorToolListenerTest {
    private lateinit var plugin: JavaPlugin
    private lateinit var player: PlayerMock
    private lateinit var sessions: EditorSessionService
    private lateinit var tool: EditorTool
    private lateinit var listener: EditorToolListener
    private val catalogs = SceneCatalogBinding.production()
    private val marker = LocalId("marker")

    @BeforeEach
    fun setUp() {
        val server = MockBukkit.mock()
        plugin = MockBukkit.createMockPlugin()
        val world = server.addSimpleWorld("build")
        player = server.addPlayer()
        player.teleport(Location(world, 0.0, 0.0, 0.0, -90f, 0f))
        player.addAttachment(plugin, "grounds.scene.edit", true)
        sessions = EditorSessionService(catalogs)
        sessions.open(world.uid, catalogs.newDocument("grounds:tool"))
        assertTrue(
            sessions
                .mutate(
                    world.uid,
                    SceneMutations.createProp(
                        player.uniqueId,
                        marker,
                        AssetKey("grounds:editor/marker"),
                        PlayerPlacement(Vec3(3.0, player.eyeLocation.y, 0.0), 0.0),
                    ),
                )
                .accepted
        )
        val buildWorld = mock(BuildWorld::class.java)
        `when`(buildWorld.world).thenReturn(Optional.of(world))
        tool = EditorTool(plugin)
        listener = EditorToolListener(tool, sessions, catalogs, PaperSessionResolver { buildWorld })
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    @Test
    fun `main-hand left click ray selects nearest element and offhand passes through`() {
        val offhand = interact(Action.LEFT_CLICK_AIR, EquipmentSlot.OFF_HAND)
        listener.onInteract(offhand)
        assertNotEquals(Event.Result.DENY, offhand.useItemInHand())
        assertEquals(null, sessions.selection(player.world.uid, player.uniqueId))

        val event = interact(Action.LEFT_CLICK_AIR, EquipmentSlot.HAND)
        listener.onInteract(event)

        assertEquals(Event.Result.DENY, event.useItemInHand())
        assertEquals(marker, sessions.selection(player.world.uid, player.uniqueId)?.elementId)
    }

    @Test
    fun `right click cycles components only with active owned selection`() {
        val inactive = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND)
        listener.onInteract(inactive)
        assertNotEquals(Event.Result.DENY, inactive.useItemInHand())

        sessions.select(player.world.uid, player.uniqueId, marker)
        val next = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND)
        listener.onInteract(next)
        assertEquals(Event.Result.DENY, next.useItemInHand())
        assertEquals(TransformComponent.Y, listener.component(player.uniqueId))

        player.isSneaking = true
        val previous = interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND)
        listener.onInteract(previous)
        assertEquals(TransformComponent.X, listener.component(player.uniqueId))
    }

    @Test
    fun `adjacent wheel applies mutation then cancels while number-key changes pass through`() {
        sessions.select(player.world.uid, player.uniqueId, marker)
        player.inventory.setItem(0, tool.createItem())
        val before =
            (sessions.session(player.world.uid)!!.document.elements.single() as Prop)
                .transform
                .position
                .x

        val accepted = PlayerItemHeldEvent(player, 0, 1)
        listener.onHeldSlot(accepted)

        assertTrue(accepted.isCancelled)
        val after =
            (sessions.session(player.world.uid)!!.document.elements.single() as Prop)
                .transform
                .position
                .x
        assertEquals(before + 0.1, after)
        val actionBar = PlainTextComponentSerializer.plainText().serialize(player.nextActionBar())
        assertTrue(actionBar.contains("marker | x="))
        assertTrue(actionBar.contains("step=0.100"))
        assertTrue(actionBar.contains("dirty=true"))
        assertTrue(actionBar.contains("lease="))

        val numberKey = PlayerItemHeldEvent(player, 0, 4)
        listener.onHeldSlot(numberKey)
        assertFalse(numberKey.isCancelled)
    }

    @Test
    fun `wheel passes through without exact tool permission selection or lease`() {
        player.inventory.setItem(0, tool.createItem())
        val noSelection = PlayerItemHeldEvent(player, 0, 1)
        listener.onHeldSlot(noSelection)
        assertFalse(noSelection.isCancelled)

        sessions.select(player.world.uid, player.uniqueId, marker)
        player.addAttachment(plugin, "grounds.scene.edit", false)
        val denied = PlayerItemHeldEvent(player, 0, 1)
        listener.onHeldSlot(denied)
        assertFalse(denied.isCancelled)

        player.inventory.setItem(0, org.bukkit.inventory.ItemStack(org.bukkit.Material.STICK))
        val ordinary = PlayerItemHeldEvent(player, 0, 1)
        listener.onHeldSlot(ordinary)
        assertFalse(ordinary.isCancelled)
    }

    @Test
    fun `invalid scale step is rejected before hotbar cancellation`() {
        sessions.select(player.world.uid, player.uniqueId, marker)
        assertTrue(
            sessions
                .mutate(
                    player.world.uid,
                    SceneMutations.setUniformScale(player.uniqueId, marker, 0.01),
                )
                .accepted
        )
        repeat(6) { listener.onInteract(interact(Action.RIGHT_CLICK_AIR, EquipmentSlot.HAND)) }
        assertEquals(TransformComponent.SCALE, listener.component(player.uniqueId))
        player.isSneaking = true
        player.inventory.setItem(0, tool.createItem())

        val rejected = PlayerItemHeldEvent(player, 0, 8)
        listener.onHeldSlot(rejected)

        assertFalse(rejected.isCancelled)
        val scale =
            (sessions.session(player.world.uid)!!.document.elements.single() as Prop)
                .transform
                .scale
        assertEquals(Vec3(0.01, 0.01, 0.01), scale)
    }

    private fun interact(action: Action, hand: EquipmentSlot) =
        PlayerInteractEvent(player, action, tool.createItem(), null, BlockFace.SELF, hand)
}
