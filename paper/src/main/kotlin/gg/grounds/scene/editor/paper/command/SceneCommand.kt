package gg.grounds.scene.editor.paper.command

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.editor.recovery.RecoveryService
import gg.grounds.scene.editor.repository.WorldSceneRepository
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.editor.session.ReloadPolicy
import gg.grounds.scene.editor.session.ReloadPreparationResult
import gg.grounds.scene.editor.session.SessionBootstrapResult
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneMetadata
import gg.grounds.scene.format.Vec3
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * Command adapter: it parses Bukkit input and delegates every document edit to common mutations.
 */
class SceneCommand(
    private val plugin: JavaPlugin,
    private val sessions: EditorSessionService,
    private val catalogs: SceneCatalogBinding,
    private val resolver: SceneWorldResolver,
    private val scheduler: SceneCommandScheduler,
    private val feedback: SceneCommandFeedback,
) : CommandExecutor, TabExecutor {
    private val invalidSources =
        mutableMapOf<UUID, gg.grounds.scene.editor.repository.SceneLoadResult.Invalid>()

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>,
    ): Boolean {
        val path = args.map { it.lowercase(Locale.ROOT) }
        val route = SceneCommandDispatcher.route(args.toList())
        if (args.isNotEmpty() && route is SceneCommandDispatcher.Route.Invalid) {
            feedback.error(sender, "Unknown or incomplete scene command.")
            return true
        }
        val authorizer = SceneCommandAuthorizer(sender::hasPermission)
        if (!authorizer.isAllowed(path)) {
            feedback.error(sender, "You do not have permission for this scene command.")
            return true
        }
        if (route is SceneCommandDispatcher.Route.CatalogStatus) {
            feedback.info(
                sender,
                "Asset catalog ${catalogs.assets.id}@${catalogs.assets.version}; action catalog ${catalogs.actions.id}@${catalogs.actions.version}.",
            )
            return true
        }
        val player =
            sender as? Player
                ?: run {
                    feedback.error(
                        sender,
                        "This scene command requires a player in a loaded build world.",
                    )
                    return true
                }
        val target =
            resolver.resolve(player)
                ?: run {
                    feedback.error(sender, "You must be in a loaded BuildSystem build world.")
                    return true
                }
        if (args.isEmpty()) {
            feedback.info(
                sender,
                "Use /scene create, info, validate, save, prop, npc, lease, recovery, or catalogs status.",
            )
            return true
        }
        if (path.first() != "create" && sessions.session(target.worldId) == null) {
            bootstrapThenResume(player, command, label, args, target.worldId, target.worldFolder)
            return true
        }
        when (route) {
            SceneCommandDispatcher.Route.Create -> create(player, target.worldId, args)
            SceneCommandDispatcher.Route.Info -> info(player, target.worldId)
            SceneCommandDispatcher.Route.Validate -> validate(player, target.worldId)
            SceneCommandDispatcher.Route.Save -> save(player, target.worldId, target.worldFolder)
            SceneCommandDispatcher.Route.History -> feedback.info(player, history(target.worldId))
            SceneCommandDispatcher.Route.Undo,
            SceneCommandDispatcher.Route.Redo ->
                historyChange(player, target.worldId, path.first(), args.getOrNull(1))
            SceneCommandDispatcher.Route.ToolGive ->
                feedback.info(player, "The editor tool is not available yet.")
            is SceneCommandDispatcher.Route.Element ->
                element(player, target.worldId, path.first(), args)
            SceneCommandDispatcher.Route.LeaseStatus,
            SceneCommandDispatcher.Route.LeaseRelease,
            SceneCommandDispatcher.Route.LeaseOverride -> lease(player, target.worldId, args)
            SceneCommandDispatcher.Route.Reload ->
                reload(player, target.worldId, target.worldFolder, ReloadPolicy.CLEAN_ONLY)
            SceneCommandDispatcher.Route.RecoveryBackup,
            SceneCommandDispatcher.Route.RecoveryExport,
            is SceneCommandDispatcher.Route.RecoveryDiscard ->
                recovery(player, target.worldId, target.worldFolder, args)
            else -> feedback.error(player, "Unknown scene command. Use /scene for available paths.")
        }
        return true
    }

    private fun bootstrapThenResume(
        player: Player,
        command: Command,
        label: String,
        args: Array<String>,
        worldId: UUID,
        root: java.nio.file.Path,
    ) {
        scheduler.asyncThenMain(
            task = { sessions.openFromRepository(worldId, WorldSceneRepository(root)) },
            apply = { result ->
                if (!player.isOnline) return@asyncThenMain
                when (result) {
                    is SessionBootstrapResult.Opened,
                    SessionBootstrapResult.AlreadyOpen -> onCommand(player, command, label, args)
                    SessionBootstrapResult.AbsentSource ->
                        feedback.info(
                            player,
                            "No scene.json exists. Use /scene create <scene-id> <display-name>.",
                        )
                    SessionBootstrapResult.EncodingFailure ->
                        feedback.error(player, "Could not open a canonical scene document.")
                    is SessionBootstrapResult.InvalidSource -> {
                        invalidSources[worldId] = result.load
                        if (
                            args.getOrNull(0)?.equals("recovery", true) == true &&
                                args.getOrNull(1)?.equals("backup-and-create", true) == true
                        )
                            recoverInvalid(player, worldId, root, args)
                        else
                            feedback.error(
                                player,
                                "scene.json is invalid and was not changed. Use /scene recovery backup-and-create <scene-id> <display-name>.",
                            )
                    }
                    is SessionBootstrapResult.RejectedSource ->
                        feedback.error(
                            player,
                            "scene.json could not be safely read: ${result.load.reason}.",
                        )
                }
            },
            failure = { error ->
                if (player.isOnline)
                    feedback.error(
                        player,
                        "Could not open scene: ${error.message ?: "I/O failure"}.",
                    )
            },
        )
        feedback.info(player, "Opening scene…")
    }

    private fun create(player: Player, worldId: java.util.UUID, args: Array<String>) {
        val sceneId =
            args.getOrNull(1)
                ?: run {
                    usage(player, "/scene create <scene-id> <display-name>")
                    return
                }
        val display =
            args.drop(2).joinToString(" ").ifBlank {
                usage(player, "/scene create <scene-id> <display-name>")
                return
            }
        val document =
            try {
                catalogs.newDocument(sceneId, SceneMetadata(sceneId, display, emptySet()))
            } catch (_: IllegalArgumentException) {
                feedback.error(player, "Invalid scene id.")
                return
            }
        val root =
            resolver.resolve(player)?.worldFolder
                ?: return feedback.error(player, "Build world is no longer available.")
        scheduler.asyncThenMain(
            task = { sessions.openFromRepository(worldId, WorldSceneRepository(root), document) },
            failure = { error ->
                if (player.isOnline)
                    feedback.error(
                        player,
                        "Could not create scene: ${error.message ?: "I/O failure"}.",
                    )
            },
        ) { result ->
            if (!player.isOnline) return@asyncThenMain
            when (result) {
                is SessionBootstrapResult.Opened ->
                    feedback.info(
                        player,
                        "Scene '${result.session.document.id.value}' is ready for editing.",
                    )
                SessionBootstrapResult.AlreadyOpen ->
                    feedback.error(player, "A scene is already open for this world.")
                SessionBootstrapResult.EncodingFailure ->
                    feedback.error(player, "Could not create a canonical scene document.")
                SessionBootstrapResult.AbsentSource ->
                    feedback.error(player, "Could not initialize a new in-memory scene.")
                is SessionBootstrapResult.InvalidSource -> {
                    invalidSources[worldId] = result.load
                    feedback.error(
                        player,
                        "scene.json is invalid and was not changed. Use /scene recovery backup-and-create <scene-id> <display-name>.",
                    )
                }
                is SessionBootstrapResult.RejectedSource ->
                    feedback.error(
                        player,
                        "scene.json could not be safely read: ${result.load.reason}.",
                    )
            }
        }
        feedback.info(player, "Opening scene…")
    }

    private fun info(player: Player, worldId: java.util.UUID) {
        val session =
            sessions.session(worldId)
                ?: return feedback.info(player, "No scene is open. Use /scene create first.")
        val actions = applicationActions(session.document).ifEmpty { "none" }
        feedback.info(
            player,
            "Scene ${session.document.id.value}: ${session.document.elements.size} elements; preserved application actions (read-only): $actions; dirty=${sessions.hasUnsavedChanges(worldId)}.",
        )
    }

    private fun validate(player: Player, worldId: java.util.UUID) {
        val session = sessions.session(worldId) ?: return feedback.info(player, "No scene is open.")
        val state =
            sessions.validation(worldId) ?: return feedback.info(player, "No scene is open.")
        val problems =
            (state.intrinsic.problems + state.catalogs.validation.problems).sortedWith(
                compareBy({ it.path }, { it.code })
            )
        if (problems.isEmpty()) feedback.info(player, "Scene validation passed.")
        else problems.take(10).forEach { feedback.error(player, "${it.path}: ${it.code}") }
    }

    private fun save(player: Player, worldId: java.util.UUID, root: java.nio.file.Path) {
        if (sessions.session(worldId) == null) return feedback.info(player, "No scene is open.")
        scheduler.asyncThenMain(
            task = { sessions.save(worldId, WorldSceneRepository(root)) },
            failure = { error ->
                if (player.isOnline)
                    feedback.error(player, "Scene save failed: ${error.message ?: "I/O failure"}.")
            },
        ) { result ->
            if (!player.isOnline) return@asyncThenMain
            if (result is gg.grounds.scene.editor.repository.SceneSaveResult.Saved)
                feedback.info(player, "Scene saved locally to scene.json.")
            else feedback.error(player, "Scene save failed: ${result::class.simpleName}.")
        }
        feedback.info(player, "Saving scene locally…")
    }

    private fun history(worldId: java.util.UUID): String =
        sessions.session(worldId)?.history?.let {
            "History: ${it.undoSize} undo, ${it.redoSize} redo."
        } ?: "No scene is open."

    private fun historyChange(
        player: Player,
        worldId: java.util.UUID,
        operation: String,
        rawSteps: String?,
    ) {
        val steps = rawSteps?.toIntOrNull() ?: 1
        if (steps <= 0) return feedback.error(player, "Steps must be a positive integer.")
        val changed =
            if (operation == "undo") sessions.undo(worldId, steps)
            else sessions.redo(worldId, steps)
        feedback.info(player, if (changed) "$operation applied." else "Nothing to $operation.")
    }

    private fun element(
        player: Player,
        worldId: java.util.UUID,
        kind: String,
        args: Array<String>,
    ) {
        if (args.getOrNull(1)?.equals("list", true) == true) {
            val elements =
                sessions.session(worldId)?.document?.elements.orEmpty().filter {
                    (kind == "prop" && it is gg.grounds.scene.format.Prop) ||
                        (kind == "npc" && it is gg.grounds.scene.format.Npc)
                }
            val page = SceneCommandParser.page(elements, args.getOrNull(2)?.toIntOrNull() ?: 1)
            return feedback.info(
                player,
                page.entries
                    .joinToString(prefix = "$kind page ${page.page}/${page.pages}: ") {
                        it.id.value
                    }
                    .ifBlank { "No $kind elements." },
            )
        }
        val id = localId(player, args.getOrNull(1)) ?: return
        val operation =
            args.getOrNull(2)?.lowercase(Locale.ROOT)
                ?: run {
                    usage(player, "/scene $kind <$kind-id> <operation>")
                    return
                }
        if (operation == "select") {
            when (val selected = sessions.select(worldId, player.uniqueId, id)) {
                is gg.grounds.scene.editor.session.SelectionResult.Selected ->
                    feedback.info(player, "Selected ${id.value}.")
                is gg.grounds.scene.editor.session.SelectionResult.Refused ->
                    feedback.error(player, "Element is leased by ${selected.owner}.")
                else -> feedback.error(player, "Element was not found.")
            }
            return
        }
        val mutation =
            try {
                mutation(player, kind, id, operation, args)
            } catch (_: IllegalArgumentException) {
                feedback.error(player, "Invalid finite numeric value or identifier.")
                return
            }
                ?: run {
                    usage(player, "Invalid /scene $kind command.")
                    return
                }
        val outcome = sessions.mutate(worldId, mutation)
        feedback.info(
            player,
            if (outcome.accepted) "${mutation.name} applied."
            else
                "Edit rejected: ${outcome.result?.let { (it as? gg.grounds.scene.editor.mutation.SceneMutationResult.Rejected)?.reason } ?: "no session"}.",
        )
    }

    private fun mutation(
        player: Player,
        kind: String,
        id: LocalId,
        operation: String,
        args: Array<String>,
    ): gg.grounds.scene.editor.mutation.SceneMutation? {
        return when (operation) {
            "create" -> {
                val asset = AssetKey(args.getOrNull(3) ?: return null)
                val placement =
                    PlayerPlacement(
                        Vec3(player.location.x, player.location.y, player.location.z),
                        player.location.yaw.toDouble(),
                    )
                if (kind == "prop") SceneMutations.createProp(player.uniqueId, id, asset, placement)
                else SceneMutations.createNpc(player.uniqueId, id, asset, placement)
            }
            "remove" -> SceneMutations.remove(player.uniqueId, id)
            "clone" ->
                SceneMutations.clone(player.uniqueId, id, LocalId(args.getOrNull(3) ?: return null))
            "scale" ->
                if (args.getOrNull(3)?.equals("set", true) == true)
                    SceneMutations.setUniformScale(player.uniqueId, id, finite(args.getOrNull(4)))
                else null
            "label" ->
                if (kind == "npc" && args.getOrNull(3)?.equals("set", true) == true)
                    SceneMutations.setLabel(
                        player.uniqueId,
                        id,
                        Component.text(args.drop(4).joinToString(" ")),
                    )
                else null
            "position" -> transformPosition(player, id, args)
            "rotation" -> transformRotation(player, id, args)
            else -> null
        }
    }

    private fun transformPosition(player: Player, id: LocalId, args: Array<String>) =
        when (args.getOrNull(3)?.lowercase(Locale.ROOT)) {
            "here" ->
                SceneMutations.placeHere(
                    player.uniqueId,
                    id,
                    PlayerPlacement(
                        Vec3(player.location.x, player.location.y, player.location.z),
                        player.location.yaw.toDouble(),
                    ),
                )
            "set" -> SceneMutations.setPosition(player.uniqueId, id, vector(args, 4))
            "add" -> SceneMutations.addPosition(player.uniqueId, id, vector(args, 4))
            else -> null
        }

    private fun transformRotation(player: Player, id: LocalId, args: Array<String>) =
        when (args.getOrNull(3)?.lowercase(Locale.ROOT)) {
            "set" ->
                SceneMutations.setRotation(
                    player.uniqueId,
                    id,
                    finite(args.getOrNull(4)),
                    finite(args.getOrNull(5)),
                    finite(args.getOrNull(6)),
                )
            "add" ->
                SceneMutations.addRotation(
                    player.uniqueId,
                    id,
                    finite(args.getOrNull(4)),
                    finite(args.getOrNull(5)),
                    finite(args.getOrNull(6)),
                )
            else -> null
        }

    private fun lease(player: Player, worldId: java.util.UUID, args: Array<String>) {
        val id = localId(player, args.getOrNull(2)) ?: return
        when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
            "release" ->
                when (val status = sessions.leaseStatus(worldId, id)) {
                    is gg.grounds.scene.editor.session.LeaseStatusResult.Held if
                        (status.lease.owner != player.uniqueId &&
                            !player.hasPermission("grounds.scene.lease.override"))
                     ->
                        feedback.error(
                            player,
                            "Only the lease owner or an override administrator may release it.",
                        )
                    else ->
                        when (val result = sessions.releaseLease(worldId, id)) {
                            is gg.grounds.scene.editor.session.LeaseReleaseResult.Released ->
                                feedback.info(player, "Lease for ${id.value} released.")
                            else -> feedback.info(player, "No active lease for ${id.value}.")
                        }
                }
            "status" ->
                when (val result = sessions.leaseStatus(worldId, id)) {
                    is gg.grounds.scene.editor.session.LeaseStatusResult.Held ->
                        feedback.info(player, "Lease for ${id.value}: ${result.lease.owner}.")
                    gg.grounds.scene.editor.session.LeaseStatusResult.Available ->
                        feedback.info(player, "Lease for ${id.value} is available.")
                    else -> feedback.info(player, "Element ${id.value} is unavailable.")
                }
            "override" ->
                when (val result = sessions.overrideSelection(worldId, player.uniqueId, id)) {
                    is gg.grounds.scene.editor.session.SelectionResult.Selected ->
                        feedback.info(player, "Lease for ${id.value} overridden and selected.")
                    else -> feedback.error(player, "Element ${id.value} is unavailable.")
                }
            else -> usage(player, "/scene lease status|release <element-id>")
        }
    }

    private fun reload(
        player: Player,
        worldId: java.util.UUID,
        root: java.nio.file.Path,
        policy: ReloadPolicy,
    ) {
        val preparation =
            when (val result = sessions.prepareReload(worldId)) {
                is ReloadPreparationResult.Prepared -> result
                ReloadPreparationResult.NoSession ->
                    return feedback.info(player, "No scene is open.")
                ReloadPreparationResult.SaveInProgress ->
                    return feedback.error(
                        player,
                        "Scene save is in progress; reload is unavailable.",
                    )
            }
        val discardedSceneId =
            sessions.session(worldId)?.document?.id?.value
                ?: return feedback.info(player, "No scene is open.")
        scheduler.asyncThenMain(
            task = { WorldSceneRepository(root).load() },
            failure = { error ->
                if (player.isOnline)
                    feedback.error(
                        player,
                        "Could not reload scene: ${error.message ?: "I/O failure"}.",
                    )
            },
        ) { load ->
            val result = sessions.replaceFromLoad(worldId, load, preparation.snapshot, policy)
            if (!player.isOnline) return@asyncThenMain
            when (result) {
                is gg.grounds.scene.editor.session.SessionReloadResult.Reloaded -> {
                    val audit = result.discardAudit
                    feedback.info(
                        player,
                        if (audit == null) "Scene reloaded from disk."
                        else
                            "Discarded scene $discardedSceneId at ${audit.discardedFingerprint} and reloaded from disk.",
                    )
                }
                gg.grounds.scene.editor.session.SessionReloadResult.DiscardConfirmationRequired ->
                    feedback.error(
                        player,
                        "Unsaved edits remain. Use /scene recovery discard-and-reload confirm.",
                    )
                gg.grounds.scene.editor.session.SessionReloadResult.StaleSession ->
                    feedback.error(
                        player,
                        "Scene changed while reloading; no edits were discarded.",
                    )
                else -> feedback.error(player, "Reload failed: ${result::class.simpleName}.")
            }
        }
        feedback.info(player, "Loading scene from disk…")
    }

    private fun recovery(
        player: Player,
        worldId: java.util.UUID,
        root: java.nio.file.Path,
        args: Array<String>,
    ) {
        val action =
            args.getOrNull(1)?.lowercase(Locale.ROOT)
                ?: run {
                    usage(
                        player,
                        "/scene recovery backup-and-create|export|discard-and-reload confirm",
                    )
                    return
                }
        when (action) {
            "backup-and-create" -> recoverInvalid(player, worldId, root, args)
            "export" ->
                run {
                    val session =
                        sessions.session(worldId)
                            ?: return feedback.info(player, "No scene is open.")
                    val document = session.document
                    scheduler.asyncThenMain(
                        task = {
                            RecoveryService(WorldSceneRepository(root), plugin.dataFolder.toPath())
                                .export(document)
                        },
                        failure = { error ->
                            if (player.isOnline)
                                feedback.error(
                                    player,
                                    "Recovery export failed: ${error.message ?: "I/O failure"}.",
                                )
                        },
                    ) { result ->
                        if (!player.isOnline) return@asyncThenMain
                        if (
                            result is gg.grounds.scene.editor.recovery.RecoveryExportResult.Exported
                        )
                            feedback.info(
                                player,
                                "Diagnostic export written to ${result.export.path.fileName}.",
                            )
                        else
                            feedback.error(
                                player,
                                "Recovery export failed: ${result::class.simpleName}.",
                            )
                    }
                }
            "discard-and-reload" -> {
                if (args.getOrNull(2) != "confirm")
                    return feedback.error(
                        player,
                        "Literal confirmation required: /scene recovery discard-and-reload confirm",
                    )
                reload(player, worldId, root, ReloadPolicy.CONFIRMED_DISCARD)
            }
            else ->
                usage(player, "/scene recovery backup-and-create|export|discard-and-reload confirm")
        }
    }

    private fun recoverInvalid(
        player: Player,
        worldId: UUID,
        root: java.nio.file.Path,
        args: Array<String>,
    ) {
        val invalid =
            invalidSources[worldId]
                ?: return feedback.error(player, "No invalid scene source is awaiting recovery.")
        val sceneId =
            args.getOrNull(2)
                ?: return usage(
                    player,
                    "/scene recovery backup-and-create <scene-id> <display-name>",
                )
        val display =
            args.drop(3).joinToString(" ").ifBlank {
                return usage(player, "/scene recovery backup-and-create <scene-id> <display-name>")
            }
        val document =
            try {
                catalogs.newDocument(sceneId, SceneMetadata(sceneId, display, emptySet()))
            } catch (_: IllegalArgumentException) {
                return feedback.error(player, "Invalid scene id.")
            }
        scheduler.asyncThenMain(
            task = {
                sessions.recoverInvalidAndOpen(
                    worldId,
                    invalid,
                    document,
                    WorldSceneRepository(root),
                )
            },
            failure = { error ->
                if (player.isOnline)
                    feedback.error(
                        player,
                        "Invalid-file recovery failed: ${error.message ?: "I/O failure"}.",
                    )
            },
        ) { result ->
            if (result is gg.grounds.scene.editor.session.InvalidRecoveryOpenResult.Opened) {
                invalidSources.remove(worldId)
                if (!player.isOnline) return@asyncThenMain
                feedback.info(
                    player,
                    "Invalid scene backed up as ${result.backup.fileName}; scene '${result.session.document.id.value}' created locally.",
                )
            } else if (player.isOnline)
                feedback.error(player, "Invalid-file recovery failed: ${result::class.simpleName}.")
        }
        feedback.info(player, "Backing up invalid scene and creating a new scene…")
    }

    private fun localId(player: Player, value: String?): LocalId? =
        try {
            LocalId(
                value
                    ?: run {
                        usage(player, "Missing element id.")
                        return null
                    }
            )
        } catch (_: IllegalArgumentException) {
            feedback.error(player, "Invalid element id.")
            null
        }

    private fun vector(args: Array<String>, index: Int) =
        Vec3(
            finite(args.getOrNull(index)),
            finite(args.getOrNull(index + 1)),
            finite(args.getOrNull(index + 2)),
        )

    private fun finite(value: String?): Double =
        SceneCommandParser.finite(value) ?: throw IllegalArgumentException("finite number required")

    private fun usage(sender: CommandSender, message: String) {
        feedback.error(sender, message)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String> = SceneTabCompleter(this).complete(sender, args)

    internal fun catalogAssets(npc: Boolean): List<String> =
        catalogs.assets.assets.values
            .filter { it.kind.name == if (npc) "NPC_BODY" else "PROP" }
            .map { it.key.value }
            .sorted()

    internal fun elementIds(sender: CommandSender?, npc: Boolean): List<String> {
        val player = sender as? Player ?: return emptyList()
        val worldId = resolver.resolve(player)?.worldId ?: return emptyList()
        return sessions
            .session(worldId)
            ?.document
            ?.elements
            .orEmpty()
            .filter {
                (npc && it is gg.grounds.scene.format.Npc) ||
                    (!npc && it is gg.grounds.scene.format.Prop)
            }
            .map { it.id.value }
            .sorted()
    }

    private fun applicationActions(document: gg.grounds.scene.format.SceneDocument): String =
        document.elements
            .filterIsInstance<gg.grounds.scene.format.Npc>()
            .flatMap { npc ->
                npc.bindings.flatMap { binding ->
                    binding.actions.filterIsInstance<gg.grounds.scene.format.ApplicationAction>()
                }
            }
            .map { it.key.value }
            .sorted()
            .joinToString(", ")
}

/**
 * Testable Paper boundary: production resolves through BuildSystem, tests inject a stable world.
 */
fun interface SceneWorldResolver {
    fun resolve(player: Player): gg.grounds.scene.editor.paper.BuildWorldTarget?
}

/**
 * Testable scheduling boundary; production preserves Paper's async-I/O then main-thread handoff.
 */
interface SceneCommandScheduler {
    fun <T> asyncThenMain(
        task: () -> T,
        failure: (Throwable) -> Unit = {},
        apply: (T) -> Unit,
    ): CompletableFuture<T>
}

interface SceneCommandFeedback {
    fun info(sender: CommandSender, message: String)

    fun error(sender: CommandSender, message: String)
}
