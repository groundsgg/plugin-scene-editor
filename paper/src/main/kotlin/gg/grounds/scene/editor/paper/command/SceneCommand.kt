package gg.grounds.scene.editor.paper.command

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.editor.paper.SceneMessage
import gg.grounds.scene.editor.paper.tool.EditorTool
import gg.grounds.scene.editor.recovery.RecoveryService
import gg.grounds.scene.editor.repository.WorldSceneRepository
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.editor.session.ReloadPolicy
import gg.grounds.scene.editor.session.ReloadPreparationResult
import gg.grounds.scene.editor.session.SessionBootstrapResult
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.NpcCollisionMode
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
    private val editorTool: EditorTool? = null,
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
            feedback.send(sender, SceneMessage.UNKNOWN_INCOMPLETE)
            return true
        }
        val authorizer = SceneCommandAuthorizer(sender::hasPermission)
        if (!authorizer.isAllowed(path)) {
            feedback.send(sender, SceneMessage.NO_PERMISSION)
            return true
        }
        if (route is SceneCommandDispatcher.Route.CatalogStatus) {
            feedback.send(
                sender,
                SceneMessage.CATALOG_STATUS,
                "assets-id" to catalogs.assets.id,
                "assets-version" to catalogs.assets.version,
                "actions-id" to catalogs.actions.id,
                "actions-version" to catalogs.actions.version,
            )
            return true
        }
        val player =
            sender as? Player
                ?: run {
                    feedback.send(sender, SceneMessage.PLAYER_WORLD_REQUIRED)
                    return true
                }
        val target =
            resolver.resolve(player)
                ?: run {
                    feedback.send(sender, SceneMessage.BUILD_WORLD_REQUIRED)
                    return true
                }
        if (args.isEmpty()) {
            feedback.send(sender, SceneMessage.HELP)
            return true
        }
        if (
            path.first() != "create" &&
                route !is SceneCommandDispatcher.Route.ToolGive &&
                sessions.session(target.worldId) == null
        ) {
            bootstrapThenResume(player, command, label, args, target.worldId, target.worldFolder)
            return true
        }
        when (route) {
            SceneCommandDispatcher.Route.Create -> create(player, target.worldId, args)
            SceneCommandDispatcher.Route.Info -> info(player, target.worldId)
            SceneCommandDispatcher.Route.Validate -> validate(player, target.worldId)
            SceneCommandDispatcher.Route.Save -> save(player, target.worldId, target.worldFolder)
            SceneCommandDispatcher.Route.History -> history(player, target.worldId)
            SceneCommandDispatcher.Route.Undo,
            SceneCommandDispatcher.Route.Redo ->
                historyChange(player, target.worldId, path.first(), args.getOrNull(1))
            SceneCommandDispatcher.Route.ToolGive -> giveTool(player)
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
            else -> feedback.send(player, SceneMessage.UNKNOWN)
        }
        return true
    }

    private fun giveTool(player: Player) {
        val available = editorTool ?: return feedback.send(player, SceneMessage.TOOL_UNAVAILABLE)
        val item = available.createItem(player)
        val leftovers = player.inventory.addItem(item)
        leftovers.values.forEach { player.world.dropItemNaturally(player.location, it) }
        feedback.send(
            player,
            if (leftovers.isEmpty()) SceneMessage.TOOL_ADDED else SceneMessage.TOOL_DROPPED,
        )
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
                        feedback.send(player, SceneMessage.NO_SOURCE)
                    SessionBootstrapResult.EncodingFailure ->
                        feedback.send(player, SceneMessage.CANONICAL_OPEN_FAILED)
                    is SessionBootstrapResult.InvalidSource -> {
                        invalidSources[worldId] = result.load
                        if (
                            args.getOrNull(0)?.equals("recovery", true) == true &&
                                args.getOrNull(1)?.equals("backup-and-create", true) == true
                        )
                            recoverInvalid(player, worldId, root, args)
                        else feedback.send(player, SceneMessage.INVALID_SOURCE)
                    }
                    is SessionBootstrapResult.RejectedSource ->
                        feedback.send(
                            player,
                            SceneMessage.REJECTED_SOURCE,
                            "reason" to result.load.reason,
                        )
                }
            },
            failure = { error ->
                if (player.isOnline)
                    feedback.send(
                        player,
                        SceneMessage.OPEN_FAILED,
                        "reason" to (error.message ?: SceneMessage.IO_FAILURE),
                    )
            },
        )
        feedback.send(player, SceneMessage.OPENING)
    }

    private fun create(player: Player, worldId: java.util.UUID, args: Array<String>) {
        val sceneId =
            args.getOrNull(1)
                ?: run {
                    usage(player, SceneMessage.USAGE_CREATE)
                    return
                }
        val display =
            args.drop(2).joinToString(" ").ifBlank {
                usage(player, SceneMessage.USAGE_CREATE)
                return
            }
        val document =
            try {
                catalogs.newDocument(sceneId, SceneMetadata(sceneId, display, emptySet()))
            } catch (_: IllegalArgumentException) {
                feedback.send(player, SceneMessage.INVALID_SCENE_ID)
                return
            }
        val root =
            resolver.resolve(player)?.worldFolder
                ?: return feedback.send(player, SceneMessage.WORLD_UNAVAILABLE)
        scheduler.asyncThenMain(
            task = { sessions.openFromRepository(worldId, WorldSceneRepository(root), document) },
            failure = { error ->
                if (player.isOnline)
                    feedback.send(
                        player,
                        SceneMessage.CREATE_FAILED,
                        "reason" to (error.message ?: SceneMessage.IO_FAILURE),
                    )
            },
        ) { result ->
            if (!player.isOnline) return@asyncThenMain
            when (result) {
                is SessionBootstrapResult.Opened ->
                    feedback.send(
                        player,
                        SceneMessage.READY,
                        "scene-id" to result.session.document.id.value,
                    )
                SessionBootstrapResult.AlreadyOpen ->
                    feedback.send(player, SceneMessage.ALREADY_OPEN)
                SessionBootstrapResult.EncodingFailure ->
                    feedback.send(player, SceneMessage.CANONICAL_CREATE_FAILED)
                SessionBootstrapResult.AbsentSource ->
                    feedback.send(player, SceneMessage.MEMORY_CREATE_FAILED)
                is SessionBootstrapResult.InvalidSource -> {
                    invalidSources[worldId] = result.load
                    feedback.send(player, SceneMessage.INVALID_SOURCE)
                }
                is SessionBootstrapResult.RejectedSource ->
                    feedback.send(
                        player,
                        SceneMessage.REJECTED_SOURCE,
                        "reason" to result.load.reason,
                    )
            }
        }
        feedback.send(player, SceneMessage.OPENING)
    }

    private fun info(player: Player, worldId: java.util.UUID) {
        val session =
            sessions.session(worldId) ?: return feedback.send(player, SceneMessage.NO_SCENE_CREATE)
        val actions =
            applicationActions(session.document).takeIf { it.isNotEmpty() }
                ?: SceneMessage.NO_ACTIONS
        feedback.send(
            player,
            SceneMessage.INFO,
            "scene-id" to session.document.id.value,
            "count" to session.document.elements.size,
            "actions" to actions,
            "dirty" to sessions.hasUnsavedChanges(worldId),
        )
    }

    private fun validate(player: Player, worldId: java.util.UUID) {
        val session =
            sessions.session(worldId) ?: return feedback.send(player, SceneMessage.NO_SCENE)
        val state =
            sessions.validation(worldId) ?: return feedback.send(player, SceneMessage.NO_SCENE)
        val problems =
            (state.intrinsic.problems + state.catalogs.validation.problems).sortedWith(
                compareBy({ it.path }, { it.code })
            )
        if (problems.isEmpty()) feedback.send(player, SceneMessage.VALIDATION_PASSED)
        else
            problems.take(10).forEach {
                feedback.send(
                    player,
                    SceneMessage.VALIDATION_PROBLEM,
                    "path" to it.path,
                    "code" to it.code,
                )
            }
    }

    private fun save(player: Player, worldId: java.util.UUID, root: java.nio.file.Path) {
        if (sessions.session(worldId) == null) return feedback.send(player, SceneMessage.NO_SCENE)
        scheduler.asyncThenMain(
            task = { sessions.save(worldId, WorldSceneRepository(root)) },
            failure = { error ->
                if (player.isOnline)
                    feedback.send(
                        player,
                        SceneMessage.SAVE_FAILED,
                        "reason" to (error.message ?: SceneMessage.IO_FAILURE),
                    )
            },
        ) { result ->
            if (!player.isOnline) return@asyncThenMain
            if (result is gg.grounds.scene.editor.repository.SceneSaveResult.Saved)
                feedback.send(player, SceneMessage.SAVED)
            else
                feedback.send(
                    player,
                    SceneMessage.SAVE_FAILED,
                    "reason" to result::class.simpleName.orEmpty(),
                )
        }
        feedback.send(player, SceneMessage.SAVING)
    }

    private fun history(player: Player, worldId: java.util.UUID) {
        val history = sessions.session(worldId)?.history
        if (history == null) feedback.send(player, SceneMessage.NO_SCENE)
        else
            feedback.send(
                player,
                SceneMessage.HISTORY,
                "undo" to history.undoSize,
                "redo" to history.redoSize,
            )
    }

    private fun historyChange(
        player: Player,
        worldId: java.util.UUID,
        operation: String,
        rawSteps: String?,
    ) {
        val steps = rawSteps?.toIntOrNull() ?: 1
        if (steps <= 0) return feedback.send(player, SceneMessage.POSITIVE_STEPS)
        val changed =
            if (operation == "undo") sessions.undo(worldId, steps)
            else sessions.redo(worldId, steps)
        feedback.send(
            player,
            if (changed) SceneMessage.HISTORY_APPLIED else SceneMessage.HISTORY_NONE,
            "operation" to operation,
        )
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
            return if (page.entries.isEmpty()) {
                feedback.send(player, SceneMessage.NO_ELEMENTS, "kind" to kind)
            } else {
                feedback.send(
                    player,
                    SceneMessage.ELEMENT_PAGE,
                    "kind" to kind,
                    "page" to page.page,
                    "pages" to page.pages,
                    "elements" to page.entries.joinToString { it.id.value },
                )
            }
        }
        val id = localId(player, args.getOrNull(1)) ?: return
        val operation =
            args.getOrNull(2)?.lowercase(Locale.ROOT)
                ?: run {
                    usage(player, SceneMessage.USAGE_ELEMENT, "kind" to kind)
                    return
                }
        if (operation == "select") {
            when (val selected = sessions.select(worldId, player.uniqueId, id)) {
                is gg.grounds.scene.editor.session.SelectionResult.Selected ->
                    feedback.send(player, SceneMessage.SELECTED, "element-id" to id.value)
                is gg.grounds.scene.editor.session.SelectionResult.Refused ->
                    feedback.send(player, SceneMessage.LEASED_BY, "owner" to selected.owner)
                else -> feedback.send(player, SceneMessage.ELEMENT_NOT_FOUND)
            }
            return
        }
        val mutation =
            try {
                mutation(player, kind, id, operation, args)
            } catch (_: IllegalArgumentException) {
                feedback.send(player, SceneMessage.INVALID_VALUE)
                return
            }
                ?: run {
                    usage(player, SceneMessage.USAGE_INVALID_ELEMENT, "kind" to kind)
                    return
                }
        val outcome = sessions.mutate(worldId, mutation)
        feedback.send(
            player,
            if (outcome.accepted) SceneMessage.MUTATION_APPLIED else SceneMessage.EDIT_REJECTED,
            if (outcome.accepted) "mutation" to mutation.name
            else
                "reason" to
                    (outcome.result?.let {
                        (it as? gg.grounds.scene.editor.mutation.SceneMutationResult.Rejected)
                            ?.reason
                    } ?: "no session"),
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
            "action" ->
                if (kind == "npc" && args.getOrNull(3)?.equals("set", true) == true)
                    SceneMutations.setApplicationAction(
                        player.uniqueId,
                        id,
                        gg.grounds.scene.format.SceneTrigger.valueOf(
                            args.getOrNull(4)?.uppercase(Locale.ROOT) ?: return null
                        ),
                        gg.grounds.scene.format.ActionKey(args.getOrNull(5) ?: return null),
                    )
                else null
            "collision" ->
                if (kind == "npc")
                    when (args.getOrNull(3)?.lowercase(Locale.ROOT)) {
                        "set" ->
                            SceneMutations.setNpcCollisionMode(
                                player.uniqueId,
                                id,
                                NpcCollisionMode.valueOf(
                                    args.getOrNull(4)?.uppercase(Locale.ROOT) ?: return null
                                ),
                            )
                        "strength" ->
                            SceneMutations.setNpcBounceStrength(
                                player.uniqueId,
                                id,
                                finite(args.getOrNull(4)),
                            )
                        "distance" ->
                            SceneMutations.setNpcBounceDistance(
                                player.uniqueId,
                                id,
                                bounceDistance(args.getOrNull(4)),
                            )
                        else -> null
                    }
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
                     -> feedback.send(player, SceneMessage.LEASE_RELEASE_FORBIDDEN)
                    else ->
                        when (val result = sessions.releaseLease(worldId, id)) {
                            is gg.grounds.scene.editor.session.LeaseReleaseResult.Released ->
                                feedback.send(
                                    player,
                                    SceneMessage.LEASE_RELEASED,
                                    "element-id" to id.value,
                                )
                            else ->
                                feedback.send(
                                    player,
                                    SceneMessage.NO_ACTIVE_LEASE,
                                    "element-id" to id.value,
                                )
                        }
                }
            "status" ->
                when (val result = sessions.leaseStatus(worldId, id)) {
                    is gg.grounds.scene.editor.session.LeaseStatusResult.Held ->
                        feedback.send(
                            player,
                            SceneMessage.LEASE_STATUS,
                            "element-id" to id.value,
                            "owner" to result.lease.owner,
                        )
                    gg.grounds.scene.editor.session.LeaseStatusResult.Available ->
                        feedback.send(
                            player,
                            SceneMessage.LEASE_AVAILABLE,
                            "element-id" to id.value,
                        )
                    else ->
                        feedback.send(
                            player,
                            SceneMessage.ELEMENT_UNAVAILABLE,
                            "element-id" to id.value,
                        )
                }
            "override" ->
                when (val result = sessions.overrideSelection(worldId, player.uniqueId, id)) {
                    is gg.grounds.scene.editor.session.SelectionResult.Selected ->
                        feedback.send(
                            player,
                            SceneMessage.LEASE_OVERRIDDEN,
                            "element-id" to id.value,
                        )
                    else ->
                        feedback.send(
                            player,
                            SceneMessage.ELEMENT_UNAVAILABLE,
                            "element-id" to id.value,
                        )
                }
            else -> usage(player, SceneMessage.USAGE_LEASE)
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
                    return feedback.send(player, SceneMessage.NO_SCENE)
                ReloadPreparationResult.SaveInProgress ->
                    return feedback.send(player, SceneMessage.SAVE_IN_PROGRESS)
            }
        val discardedSceneId =
            sessions.session(worldId)?.document?.id?.value
                ?: return feedback.send(player, SceneMessage.NO_SCENE)
        scheduler.asyncThenMain(
            task = { WorldSceneRepository(root).load() },
            failure = { error ->
                if (player.isOnline)
                    feedback.send(
                        player,
                        SceneMessage.RELOAD_FAILED,
                        "reason" to (error.message ?: SceneMessage.IO_FAILURE),
                    )
            },
        ) { load ->
            val result = sessions.replaceFromLoad(worldId, load, preparation.snapshot, policy)
            if (!player.isOnline) return@asyncThenMain
            when (result) {
                is gg.grounds.scene.editor.session.SessionReloadResult.Reloaded -> {
                    val audit = result.discardAudit
                    feedback.send(
                        player,
                        if (audit == null) SceneMessage.RELOADED
                        else SceneMessage.DISCARDED_RELOADED,
                        "scene-id" to discardedSceneId,
                        "fingerprint" to (audit?.discardedFingerprint ?: ""),
                    )
                }
                gg.grounds.scene.editor.session.SessionReloadResult.DiscardConfirmationRequired ->
                    feedback.send(player, SceneMessage.DISCARD_CONFIRMATION)
                gg.grounds.scene.editor.session.SessionReloadResult.StaleSession ->
                    feedback.send(player, SceneMessage.STALE_RELOAD)
                else ->
                    feedback.send(
                        player,
                        SceneMessage.RELOAD_FAILED,
                        "reason" to result::class.simpleName.orEmpty(),
                    )
            }
        }
        feedback.send(player, SceneMessage.LOADING)
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
                    usage(player, SceneMessage.USAGE_RECOVERY)
                    return
                }
        when (action) {
            "backup-and-create" -> recoverInvalid(player, worldId, root, args)
            "export" ->
                run {
                    val session =
                        sessions.session(worldId)
                            ?: return feedback.send(player, SceneMessage.NO_SCENE)
                    val document = session.document
                    scheduler.asyncThenMain(
                        task = {
                            RecoveryService(WorldSceneRepository(root), plugin.dataFolder.toPath())
                                .export(document)
                        },
                        failure = { error ->
                            if (player.isOnline)
                                feedback.send(
                                    player,
                                    SceneMessage.EXPORT_FAILED,
                                    "reason" to (error.message ?: SceneMessage.IO_FAILURE),
                                )
                        },
                    ) { result ->
                        if (!player.isOnline) return@asyncThenMain
                        if (
                            result is gg.grounds.scene.editor.recovery.RecoveryExportResult.Exported
                        )
                            feedback.send(
                                player,
                                SceneMessage.EXPORT_WRITTEN,
                                "file" to result.export.path.fileName,
                            )
                        else
                            feedback.send(
                                player,
                                SceneMessage.EXPORT_FAILED,
                                "reason" to result::class.simpleName.orEmpty(),
                            )
                    }
                }
            "discard-and-reload" -> {
                if (args.getOrNull(2) != "confirm")
                    return feedback.send(player, SceneMessage.LITERAL_CONFIRMATION)
                reload(player, worldId, root, ReloadPolicy.CONFIRMED_DISCARD)
            }
            else -> usage(player, SceneMessage.USAGE_RECOVERY)
        }
    }

    private fun recoverInvalid(
        player: Player,
        worldId: UUID,
        root: java.nio.file.Path,
        args: Array<String>,
    ) {
        val invalid =
            invalidSources[worldId] ?: return feedback.send(player, SceneMessage.NO_INVALID_SOURCE)
        val sceneId = args.getOrNull(2) ?: return usage(player, SceneMessage.USAGE_RECOVER_CREATE)
        val display =
            args.drop(3).joinToString(" ").ifBlank {
                return usage(player, SceneMessage.USAGE_RECOVER_CREATE)
            }
        val document =
            try {
                catalogs.newDocument(sceneId, SceneMetadata(sceneId, display, emptySet()))
            } catch (_: IllegalArgumentException) {
                return feedback.send(player, SceneMessage.INVALID_SCENE_ID)
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
                    feedback.send(
                        player,
                        SceneMessage.RECOVERY_FAILED,
                        "reason" to (error.message ?: SceneMessage.IO_FAILURE),
                    )
            },
        ) { result ->
            if (result is gg.grounds.scene.editor.session.InvalidRecoveryOpenResult.Opened) {
                invalidSources.remove(worldId)
                if (!player.isOnline) return@asyncThenMain
                feedback.send(
                    player,
                    SceneMessage.RECOVERED,
                    "backup" to result.backup.fileName,
                    "scene-id" to result.session.document.id.value,
                )
            } else if (player.isOnline)
                feedback.send(
                    player,
                    SceneMessage.RECOVERY_FAILED,
                    "reason" to result::class.simpleName.orEmpty(),
                )
        }
        feedback.send(player, SceneMessage.RECOVERING)
    }

    private fun localId(player: Player, value: String?): LocalId? =
        try {
            LocalId(
                value
                    ?: run {
                        usage(player, SceneMessage.MISSING_ELEMENT_ID)
                        return null
                    }
            )
        } catch (_: IllegalArgumentException) {
            feedback.send(player, SceneMessage.INVALID_ELEMENT_ID)
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

    private fun bounceDistance(value: String?): Double =
        SceneCommandParser.bounceDistance(value)
            ?: throw IllegalArgumentException("bounce distance required")

    private fun usage(
        sender: CommandSender,
        message: SceneMessage,
        vararg args: Pair<String, Any>,
    ) = feedback.send(sender, message, *args)

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

    internal fun catalogActions(sender: CommandSender?): List<String> {
        val player = sender as? Player ?: return emptyList()
        val worldId = resolver.resolve(player)?.worldId ?: return emptyList()
        val document = sessions.session(worldId)?.document ?: return emptyList()
        return catalogs.parameterlessActionsFor(document).map { it.key.value }.sorted()
    }

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

fun interface SceneCommandFeedback {
    fun send(sender: CommandSender, message: SceneMessage, vararg args: Pair<String, Any>)
}
