package gg.grounds.scene.editor.paper.modeltest

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.paper.preview.BukkitPreviewEntityFactory
import gg.grounds.scene.editor.paper.preview.PaperPreviewAdapter
import gg.grounds.scene.editor.session.SessionPreviewSnapshot
import gg.grounds.scene.format.*
import java.net.URI
import java.util.HexFormat
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask

internal enum class GalleryPlayback {
    IDLE,
    IDLE2,
    GREETING,
}

/** A disposable scene for one builder; it never enters an editor session or writes a world. */
internal fun npcPreviewGallery(
    catalogs: SceneCatalogBinding,
    origin: Transform,
    all: Boolean,
    playback: GalleryPlayback,
): SceneDocument {
    val paths =
        listOf(
            "guildfolk/forge_master",
            "craftmasters/shipman",
            "townfolk/builder",
            "townfolk/ragpicker",
            "guildfolk/crystal_prospector",
        )
    val keys =
        if (all)
            catalogs.assets.assets
                .filter { (key, value) ->
                    key.value.startsWith("grounds:npc/") && value.kind == AssetKind.NPC_BODY
                }
                .keys
                .sortedBy { it.value }
        else paths.map { AssetKey("grounds:npc/$it") }
    val yaw = Math.toRadians(origin.rotation.yaw)
    return catalogs.newDocument(
        "grounds:npc-preview",
        SceneMetadata("NPC-Modelltest", null, emptySet()),
        keys.mapIndexed { index, key ->
            val definition =
                requireNotNull(catalogs.assets.assets[key]) { "NPC model not installed: $key" }
            require(definition.kind == AssetKind.NPC_BODY)
            val x = ((index % 5) - (minOf(keys.size, 5) - 1) / 2.0) * 4
            val z = 6.0 + (index / 5) * 5
            val id = LocalId("model-${index}")
            val greeting =
                LocalId(if (LocalId("hello") in definition.animations) "hello" else "wave")
            val initial =
                when (playback) {
                    GalleryPlayback.IDLE -> LocalId("idle")
                    GalleryPlayback.IDLE2 -> LocalId("idle2")
                    GalleryPlayback.GREETING -> greeting
                }
            require(initial in definition.animations && greeting in definition.animations)
            val bounds = requireNotNull(definition.defaultBounds)
            Npc(
                id = id,
                group = null,
                transform =
                    Transform(
                        Vec3(
                            origin.position.x + cos(yaw) * x + sin(yaw) * z,
                            origin.position.y,
                            origin.position.z - sin(yaw) * x + cos(yaw) * z,
                        ),
                        EulerRotation(origin.rotation.yaw + 180, 0.0, 0.0),
                        Vec3(1.0, 1.0, 1.0),
                    ),
                body = key,
                label =
                    Component.text(
                        key.value.substringAfterLast('/').split('_').joinToString(" ") {
                            it.replaceFirstChar(Char::uppercaseChar)
                        }
                    ),
                labelOffset = Vec3(0.0, bounds.center.y + bounds.size.y / 2 + .3, 0.0),
                look = LookBehavior.Fixed,
                initialAnimation = initial,
                interactionBounds = bounds,
                proximity = ProximitySensor(4.0, 5.0),
                bindings =
                    listOf(
                        TriggerBinding(
                            SceneTrigger.PROXIMITY_ENTER,
                            emptyList(),
                            15000,
                            0,
                            listOf(StartAnimationAction(ElementTarget(id, null), greeting)),
                        )
                    ),
            )
        },
    )
}

internal data class PreviewPack(val url: String, val sha1: String) {
    init {
        val uri = URI(url)
        require(
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.fragment == null
        )
        require(sha1.matches(Regex("[0-9a-f]{40}")))
    }
}

/** One shared main-thread tick, with a separately identified, additive pack for each tester. */
internal class NpcPreviewGallery(
    private val plugin: JavaPlugin,
    private val catalogs: SceneCatalogBinding = SceneCatalogBinding.production(),
    private val previews: PaperPreviewAdapter =
        PaperPreviewAdapter(BukkitPreviewEntityFactory(plugin)),
    private val pack: PreviewPack? = configuredPack(plugin),
) : TabExecutor, Listener, AutoCloseable {
    private class Gallery(
        val worldId: UUID,
        val origin: Transform,
        var all: Boolean,
        var playback: GalleryPlayback,
        var snapshot: SessionPreviewSnapshot,
        var loaded: Boolean,
        val packId: UUID = UUID.randomUUID(),
    )

    private val hover = NpcGalleryHover()
    private val galleries = mutableMapOf<UUID, Gallery>()
    private var generation = 0L
    private var task: BukkitTask? = null
    private var closed = false

    fun register() {
        val command = requireNotNull(plugin.getCommand("npcpreview"))
        command.setExecutor(this)
        command.tabCompleter = this
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info(
            "NPC preview: ${catalogs.assets.assets.count { (key, value) -> key.value.startsWith("grounds:npc/") && value.kind == AssetKind.NPC_BODY }} models; addon configured=${pack != null}"
        )
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): Boolean {
        val player = sender as? Player ?: return true
        if (closed || !player.hasPermission("grounds.scene.edit")) return true
        val action = args.singleOrNull()?.lowercase() ?: if (args.isEmpty()) "queue" else "?"
        if (action == "stop") {
            stop(player.uniqueId, player)
            player.sendMessage(Component.text("Modellvorschau entfernt."))
            return true
        }
        if (action !in listOf("queue", "all", "idle", "idle2", "greet")) {
            player.sendMessage(Component.text("/npcpreview [queue|all|idle|idle2|greet|stop]"))
            return true
        }
        val selectedPack = pack
        if (
            selectedPack == null ||
                AssetKey("grounds:npc/townfolk/builder") !in catalogs.assets.assets
        ) {
            player.sendMessage(
                Component.text("Der NPC-Modelltest ist auf diesem Server nicht eingerichtet.")
            )
            return true
        }
        val previous = galleries[player.uniqueId]
        if (previous != null && previous.worldId != player.world.uid) stop(player.uniqueId, player)
        val old = galleries[player.uniqueId]
        val location = player.location
        val origin =
            old?.origin
                ?: Transform(
                    Vec3(location.x, location.y, location.z),
                    EulerRotation(-location.yaw.toDouble(), 0.0, 0.0),
                    Vec3(1.0, 1.0, 1.0),
                )
        val all = if (action == "all") true else if (action == "queue") false else old?.all ?: false
        val playback =
            when (action) {
                "idle2" -> GalleryPlayback.IDLE2
                "greet" -> GalleryPlayback.GREETING
                else -> GalleryPlayback.IDLE
            }
        val scene = npcPreviewGallery(catalogs, origin, all, playback)
        val state =
            Gallery(
                player.world.uid,
                origin,
                all,
                playback,
                SessionPreviewSnapshot(player.world.uid, scene, ++generation, emptyMap()),
                old?.loaded == true,
                old?.packId ?: UUID.randomUUID(),
            )
        previews.clearViewer(player.uniqueId)
        galleries[player.uniqueId] = state
        if (old == null) {
            try {
                player.addResourcePack(
                    state.packId,
                    selectedPack.url,
                    HexFormat.of().parseHex(selectedPack.sha1),
                    "Grounds NPC-Modelltest",
                    false,
                )
            } catch (error: RuntimeException) {
                galleries.remove(player.uniqueId)
                throw error
            }
        }
        if (task == null)
            task = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::tick), 1, 2)
        player.sendMessage(
            Component.text(
                "NPC-Vorschau: ${scene.elements.size} Modelle. Testpack annehmen; zur Begrüßung herangehen. Hover-Test: innerhalb von 5 Blöcken ansehen (türkise Umrandung). /npcpreview idle2, greet oder stop."
            )
        )
        return true
    }

    @EventHandler
    fun packStatus(event: PlayerResourcePackStatusEvent) {
        val state = galleries[event.player.uniqueId] ?: return
        if (event.getID() != state.packId) return
        when (event.status.name) {
            "SUCCESSFULLY_LOADED" -> state.loaded = true
            "DECLINED",
            "FAILED_DOWNLOAD",
            "INVALID_URL",
            "FAILED_RELOAD",
            "DISCARDED" -> {
                stop(event.player.uniqueId, event.player)
                event.player.sendMessage(
                    Component.text(
                        "Testpack nicht geladen (${event.status.name}). Mit /npcpreview erneut versuchen."
                    )
                )
            }
        }
    }

    internal fun tick() {
        if (closed) return
        for ((id, state) in galleries.toMap()) {
            val player = plugin.server.getPlayer(id)
            if (player == null || !player.isOnline || player.world.uid != state.worldId)
                stop(id, player)
            else if (state.loaded) {
                try {
                    previews.reconcile(player, state.snapshot)
                    val eye = player.eyeLocation
                    val direction = eye.direction
                    val target =
                        hover.update(
                            id,
                            state.snapshot.document,
                            Vec3(eye.x, eye.y, eye.z),
                            Vec3(direction.x, direction.y, direction.z),
                        )
                    previews.applyHover(player, target)
                } catch (error: RuntimeException) {
                    stop(id, player)
                    plugin.logger.warning("NPC preview stopped: ${error.message}")
                    player.sendMessage(
                        Component.text(
                            "Die Modellvorschau wurde wegen eines Renderfehlers beendet."
                        )
                    )
                }
            }
        }
        if (galleries.isEmpty()) {
            task?.cancel()
            task = null
        }
    }

    private fun stop(id: UUID, player: Player?) {
        val removed = galleries.remove(id)
        hover.removeViewer(id)
        previews.clearViewer(id)
        if (removed != null && player?.isOnline == true) player.removeResourcePack(removed.packId)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String> =
        if (args.size == 1 && sender.hasPermission("grounds.scene.edit"))
            listOf("queue", "all", "idle", "idle2", "greet", "stop").filter {
                it.startsWith(args[0], true)
            }
        else emptyList()

    override fun close() {
        if (closed) return
        closed = true
        task?.cancel()
        task = null
        HandlerList.unregisterAll(this)
        for (id in galleries.keys.toList()) stop(id, plugin.server.getPlayer(id))
        previews.close()
    }

    companion object {

        private fun configuredPack(plugin: JavaPlugin): PreviewPack? {
            val url =
                System.getenv("GROUNDS_NPC_PREVIEW_PACK_URL")
                    ?: plugin.config.getString("npc-preview.pack-url")
                    ?: return null
            val hash =
                System.getenv("GROUNDS_NPC_PREVIEW_PACK_SHA1")
                    ?: plugin.config.getString("npc-preview.pack-sha1")
                    ?: return null
            return PreviewPack(url, hash)
        }
    }
}
