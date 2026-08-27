package gg.grounds.scene.editor.paper.tool

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.paper.PaperSessionResolver
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.editor.session.LeaseStatusResult
import gg.grounds.scene.editor.session.SelectionResult
import gg.grounds.scene.editor.tool.RaySelection
import gg.grounds.scene.editor.tool.TransformComponent
import gg.grounds.scene.editor.tool.TransformMath
import gg.grounds.scene.editor.tool.TransformStep
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.SceneElement
import gg.grounds.scene.format.Vec3
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.math.PI
import net.kyori.adventure.text.Component
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.inventory.EquipmentSlot
import org.joml.Quaternionf
import org.joml.Vector3f

/** Main-thread input adapter. Events pass through unless a valid editor action is accepted. */
class EditorToolListener(
    private val tool: EditorTool,
    private val sessions: EditorSessionService,
    private val catalogs: SceneCatalogBinding,
    private val resolver: PaperSessionResolver,
    private val clock: Clock = Clock.systemUTC(),
) : Listener {
    private val components = mutableMapOf<UUID, TransformComponent>()

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND || !tool.isTool(event.item)) return
        val player = event.player
        if (!player.hasPermission(EDIT_PERMISSION)) return
        val target = resolver.resolve(player) ?: return
        val snapshot = sessions.previewSnapshot(target.worldId) ?: return
        when (event.action) {
            Action.LEFT_CLICK_AIR,
            Action.LEFT_CLICK_BLOCK -> {
                val eye = player.eyeLocation
                val direction = eye.direction
                val hit =
                    RaySelection.nearest(
                        RaySelection.Ray(
                            Vec3(eye.x, eye.y, eye.z),
                            Vec3(direction.x, direction.y, direction.z),
                        ),
                        snapshot.document.elements.mapNotNull(::selectionTarget),
                        MAX_SELECTION_DISTANCE,
                    ) ?: return
                if (
                    sessions.select(target.worldId, player.uniqueId, hit.target.id)
                        !is SelectionResult.Selected
                )
                    return
                consume(event)
                showStatus(player.uniqueId, target.worldId, player::sendActionBar)
            }
            Action.RIGHT_CLICK_AIR,
            Action.RIGHT_CLICK_BLOCK -> {
                if (!ownsActiveSelection(target.worldId, player.uniqueId)) return
                val current = components[player.uniqueId] ?: TransformComponent.X
                components[player.uniqueId] =
                    if (player.isSneaking) current.previous() else current.next()
                consume(event)
                showStatus(player.uniqueId, target.worldId, player::sendActionBar)
            }
            else -> Unit
        }
    }

    @EventHandler
    fun onHeldSlot(event: PlayerItemHeldEvent) {
        val player = event.player
        if (!tool.isTool(player.inventory.getItem(event.previousSlot))) return
        if (!player.hasPermission(EDIT_PERMISSION)) return
        val direction = wheelDirection(event.previousSlot, event.newSlot) ?: return
        val target = resolver.resolve(player) ?: return
        val selection = sessions.selection(target.worldId, player.uniqueId) ?: return
        val lease = sessions.leaseStatus(target.worldId, selection.elementId)
        if (lease !is LeaseStatusResult.Held || lease.lease.owner != player.uniqueId) return
        val element =
            sessions.session(target.worldId)?.document?.elements?.firstOrNull {
                it.id == selection.elementId
            } ?: return
        val component = components[player.uniqueId] ?: TransformComponent.X
        val step =
            when {
                player.isSneaking -> TransformStep.FINE
                player.isSprinting -> TransformStep.COARSE
                else -> TransformStep.NORMAL
            }
        val mutation =
            try {
                TransformMath.mutation(
                    player.uniqueId,
                    selection.elementId,
                    element.transform,
                    component,
                    step,
                    direction,
                )
            } catch (_: IllegalArgumentException) {
                return
            }
        if (!sessions.mutate(target.worldId, mutation).accepted) return
        event.isCancelled = true
        showStatus(player.uniqueId, target.worldId, player::sendActionBar, step)
    }

    fun clearPlayer(playerId: UUID) {
        components.remove(playerId)
    }

    internal fun component(playerId: UUID): TransformComponent =
        components[playerId] ?: TransformComponent.X

    private fun ownsActiveSelection(worldId: UUID, playerId: UUID): Boolean {
        val selection = sessions.selection(worldId, playerId) ?: return false
        val lease = sessions.leaseStatus(worldId, selection.elementId)
        return lease is LeaseStatusResult.Held && lease.lease.owner == playerId
    }

    private fun showStatus(
        playerId: UUID,
        worldId: UUID,
        send: (Component) -> Unit,
        step: TransformStep = TransformStep.NORMAL,
    ) {
        val selection = sessions.selection(worldId, playerId) ?: return
        val element =
            sessions.session(worldId)?.document?.elements?.firstOrNull {
                it.id == selection.elementId
            } ?: return
        val component = components[playerId] ?: TransformComponent.X
        val lease = sessions.leaseStatus(worldId, selection.elementId) as? LeaseStatusResult.Held
        val seconds =
            lease?.lease?.expiresAt?.let { expires ->
                Duration.between(clock.instant(), expires).seconds.coerceAtLeast(0)
            } ?: 0
        send(
            Component.text(
                "${selection.elementId.value} | ${component.name.lowercase()}=" +
                    "${format(component.value(element.transform))} | " +
                    "step=${format(TransformMath.amount(component, step))} | " +
                    "dirty=${sessions.hasUnsavedChanges(worldId)} | lease=${seconds}s"
            )
        )
    }

    private fun selectionTarget(element: SceneElement): RaySelection.Target? {
        if (!element.visible) return null
        val bounds =
            when (element) {
                is Npc -> element.interactionBounds
                is Prop ->
                    catalogs.assets.assets[element.asset]?.defaultBounds ?: DEFAULT_PROP_BOUNDS
                else -> return null
            }
        val position = element.transform.position
        val scale = element.transform.scale
        val half = Vec3(bounds.size.x / 2.0, bounds.size.y / 2.0, bounds.size.z / 2.0)
        val rotation =
            element.transform.rotation.let {
                Quaternionf()
                    .rotateYXZ(
                        (it.yaw * PI / 180.0).toFloat(),
                        (it.pitch * PI / 180.0).toFloat(),
                        (it.roll * PI / 180.0).toFloat(),
                    )
            }
        val corners =
            listOf(-1.0, 1.0).flatMap { xSign ->
                listOf(-1.0, 1.0).flatMap { ySign ->
                    listOf(-1.0, 1.0).map { zSign ->
                        Vector3f(
                                ((bounds.center.x + half.x * xSign) * scale.x).toFloat(),
                                ((bounds.center.y + half.y * ySign) * scale.y).toFloat(),
                                ((bounds.center.z + half.z * zSign) * scale.z).toFloat(),
                            )
                            .also(rotation::transform)
                    }
                }
            }
        return RaySelection.Target(
            element.id,
            RaySelection.Bounds(
                Vec3(
                    position.x + corners.minOf { it.x() },
                    position.y + corners.minOf { it.y() },
                    position.z + corners.minOf { it.z() },
                ),
                Vec3(
                    position.x + corners.maxOf { it.x() },
                    position.y + corners.maxOf { it.y() },
                    position.z + corners.maxOf { it.z() },
                ),
            ),
        )
    }

    private fun consume(event: PlayerInteractEvent) {
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        event.isCancelled = true
    }

    private fun wheelDirection(previous: Int, next: Int): Int? =
        when ((next - previous + HOTBAR_SIZE) % HOTBAR_SIZE) {
            1 -> 1
            HOTBAR_SIZE - 1 -> -1
            else -> null
        }

    private fun format(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)

    private companion object {
        const val EDIT_PERMISSION = "grounds.scene.edit"
        const val MAX_SELECTION_DISTANCE = 12.0
        const val HOTBAR_SIZE = 9
        val DEFAULT_PROP_BOUNDS = LocalBounds(Vec3(0.0, 0.5, 0.0), Vec3(1.0, 1.0, 1.0))
    }
}
