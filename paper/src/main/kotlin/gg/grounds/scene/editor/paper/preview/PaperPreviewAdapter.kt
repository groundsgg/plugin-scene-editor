package gg.grounds.scene.editor.paper.preview

import gg.grounds.scene.editor.session.SessionPreviewSnapshot
import gg.grounds.scene.format.CompositeProp
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.SceneElement
import gg.grounds.scene.format.Transform
import java.util.UUID
import java.util.logging.Logger
import kotlin.math.PI
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Transformation as BukkitTransformation
import org.joml.Quaternionf
import org.joml.Vector3f

/** Main-thread adapter from immutable Common snapshots to disposable, per-viewer entities. */
class PaperPreviewAdapter(
    private val factory: PreviewEntityFactory,
    private val registry: PreviewRegistry<PreviewDescriptor, PreviewHandle> = PreviewRegistry(),
) : AutoCloseable {
    fun reconcile(viewer: Player, snapshot: SessionPreviewSnapshot): ReconcileResult {
        if (viewer.world.uid != snapshot.worldId) return ReconcileResult.STALE
        val selected = snapshot.selections[viewer.uniqueId]?.elementId
        val descriptors =
            snapshot.document.elements
                .asSequence()
                .filter(SceneElement::visible)
                .map { element -> descriptor(element, element.id == selected) }
                .associateBy { it.elementId }
        return registry.reconcile(
            snapshot.worldId,
            snapshot.generation,
            viewer.uniqueId,
            descriptors,
            { factory.create(viewer, it) },
            PreviewHandle::remove,
        )
    }

    fun clearViewer(worldId: UUID, viewerId: UUID): Int =
        registry.clearViewer(worldId, viewerId, PreviewHandle::remove)

    fun clearViewer(viewerId: UUID): Int = registry.clearViewer(viewerId, PreviewHandle::remove)

    fun clearWorld(worldId: UUID): Int = registry.clearWorld(worldId, PreviewHandle::remove)

    override fun close() {
        registry.clearAll(PreviewHandle::remove)
    }

    private fun descriptor(element: SceneElement, selected: Boolean): PreviewDescriptor =
        when (element) {
            is Prop ->
                PreviewDescriptor(
                    element.id.value,
                    PreviewKind.PROP,
                    element.transform,
                    element.asset.value,
                    null,
                    selected,
                )
            is CompositeProp ->
                PreviewDescriptor(
                    element.id.value,
                    PreviewKind.PROP,
                    element.transform,
                    "composite (${element.parts.size})",
                    null,
                    selected,
                )
            is Npc ->
                PreviewDescriptor(
                    element.id.value,
                    PreviewKind.NPC,
                    element.transform,
                    element.body.value,
                    element.label,
                    selected,
                )
        }
}

enum class PreviewKind {
    PROP,
    NPC,
}

data class PreviewDescriptor(
    val elementId: String,
    val kind: PreviewKind,
    val transform: Transform,
    val asset: String,
    val label: Component?,
    val selected: Boolean,
)

fun interface PreviewEntityFactory {
    fun create(viewer: Player, descriptor: PreviewDescriptor): PreviewHandle
}

class PreviewHandle(entities: List<Entity>) {
    private val entities = entities.toList()

    fun remove() {
        entities.forEach { entity ->
            try {
                entity.remove()
            } catch (_: RuntimeException) {}
        }
    }
}

/** Visible generic renderer used until a production asset renderer is connected. */
class BukkitPreviewEntityFactory(
    private val plugin: JavaPlugin,
    private val logger: Logger = plugin.logger,
    private val spawner: PreviewEntitySpawner = BukkitPreviewEntitySpawner,
) : PreviewEntityFactory {
    private val previewKey = NamespacedKey(plugin, "preview")
    private val roleKey = NamespacedKey(plugin, "preview_role")
    private val worldKey = NamespacedKey(plugin, "preview_world")
    private val elementKey = NamespacedKey(plugin, "preview_element")

    override fun create(viewer: Player, descriptor: PreviewDescriptor): PreviewHandle {
        val created = mutableListOf<Entity>()
        return try {
            created += body(viewer, descriptor)
            descriptor.label?.let { created += text(viewer, descriptor, "label", it, 2.2) }
            if (descriptor.selected) {
                created += outline(viewer, descriptor)
                created +=
                    axis(viewer, descriptor, "axis_x", Material.RED_STAINED_GLASS, 0.8, 0.04, 0.04)
                created +=
                    axis(viewer, descriptor, "axis_y", Material.LIME_STAINED_GLASS, 0.04, 0.8, 0.04)
                created +=
                    axis(viewer, descriptor, "axis_z", Material.BLUE_STAINED_GLASS, 0.04, 0.04, 0.8)
                created += text(viewer, descriptor, "id", Component.text(descriptor.elementId), 1.4)
            }
            PreviewHandle(created)
        } catch (failure: RuntimeException) {
            created.forEach(Entity::remove)
            logger.warning(
                "Preview rendering failed for ${descriptor.elementId}; using text fallback: ${failure.message}"
            )
            PreviewHandle(
                listOf(
                    text(
                        viewer,
                        descriptor,
                        "fallback",
                        Component.text("⚠ ${descriptor.elementId}"),
                        1.0,
                    )
                )
            )
        }
    }

    private fun body(viewer: Player, descriptor: PreviewDescriptor): BlockDisplay =
        block(
            viewer,
            descriptor,
            "body",
            if (descriptor.kind == PreviewKind.NPC) Material.LIGHT_BLUE_STAINED_GLASS
            else Material.LIME_STAINED_GLASS,
            if (descriptor.kind == PreviewKind.NPC) Vector3f(0.6f, 1.8f, 0.6f)
            else Vector3f(0.8f, 0.8f, 0.8f),
            false,
        )

    private fun outline(viewer: Player, descriptor: PreviewDescriptor): BlockDisplay =
        block(
            viewer,
            descriptor,
            "outline",
            Material.WHITE_STAINED_GLASS,
            if (descriptor.kind == PreviewKind.NPC) Vector3f(0.64f, 1.84f, 0.64f)
            else Vector3f(0.84f, 0.84f, 0.84f),
            true,
        )

    private fun axis(
        viewer: Player,
        descriptor: PreviewDescriptor,
        role: String,
        material: Material,
        x: Double,
        y: Double,
        z: Double,
    ): BlockDisplay =
        block(
            viewer,
            descriptor,
            role,
            material,
            Vector3f(x.toFloat(), y.toFloat(), z.toFloat()),
            false,
        )

    private fun block(
        viewer: Player,
        descriptor: PreviewDescriptor,
        role: String,
        material: Material,
        baseScale: Vector3f,
        glowing: Boolean,
    ): BlockDisplay {
        val location = location(viewer.world, descriptor)
        val display =
            spawner.spawn(viewer.world, location, BlockDisplay::class.java) { display ->
                configure(viewer, display, descriptor, role)
                display.block = material.createBlockData()
                display.isGlowing = glowing
                if (glowing) display.glowColorOverride = Color.AQUA
                display.transformation = transformation(descriptor, baseScale)
            }
        return reveal(viewer, display)
    }

    private fun text(
        viewer: Player,
        descriptor: PreviewDescriptor,
        role: String,
        component: Component,
        yOffset: Double,
    ): TextDisplay {
        val location = location(viewer.world, descriptor).add(0.0, yOffset, 0.0)
        val display =
            spawner.spawn(viewer.world, location, TextDisplay::class.java) { display ->
                configure(viewer, display, descriptor, role)
                display.text(component)
                display.billboard = org.bukkit.entity.Display.Billboard.CENTER
            }
        return reveal(viewer, display)
    }

    private fun configure(
        viewer: Player,
        entity: Entity,
        descriptor: PreviewDescriptor,
        role: String,
    ) {
        entity.isPersistent = false
        entity.isVisibleByDefault = false
        entity.persistentDataContainer.set(previewKey, PersistentDataType.BYTE, 1.toByte())
        entity.persistentDataContainer.set(roleKey, PersistentDataType.STRING, role)
        entity.persistentDataContainer.set(
            worldKey,
            PersistentDataType.STRING,
            viewer.world.uid.toString(),
        )
        entity.persistentDataContainer.set(
            elementKey,
            PersistentDataType.STRING,
            descriptor.elementId,
        )
    }

    private fun <T : Entity> reveal(viewer: Player, entity: T): T =
        try {
            viewer.showEntity(plugin, entity)
            entity
        } catch (failure: RuntimeException) {
            entity.remove()
            throw failure
        }

    private fun location(world: World, descriptor: PreviewDescriptor): Location =
        descriptor.transform.position.let { Location(world, it.x, it.y, it.z) }

    private fun transformation(
        descriptor: PreviewDescriptor,
        baseScale: Vector3f,
    ): BukkitTransformation {
        val transform = descriptor.transform
        val rotation = transform.rotation
        val scale = transform.scale
        return BukkitTransformation(
            Vector3f(),
            Quaternionf()
                .rotateYXZ(
                    (rotation.yaw * PI / 180.0).toFloat(),
                    (rotation.pitch * PI / 180.0).toFloat(),
                    (rotation.roll * PI / 180.0).toFloat(),
                ),
            Vector3f(
                baseScale.x * scale.x.toFloat(),
                baseScale.y * scale.y.toFloat(),
                baseScale.z * scale.z.toFloat(),
            ),
            Quaternionf(),
        )
    }
}

interface PreviewEntitySpawner {
    fun <T : Entity> spawn(
        world: World,
        location: Location,
        type: Class<T>,
        configure: (T) -> Unit,
    ): T
}

object BukkitPreviewEntitySpawner : PreviewEntitySpawner {
    override fun <T : Entity> spawn(
        world: World,
        location: Location,
        type: Class<T>,
        configure: (T) -> Unit,
    ): T = world.spawn(location, type, configure)
}
