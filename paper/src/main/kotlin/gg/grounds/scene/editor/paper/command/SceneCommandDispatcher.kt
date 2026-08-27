package gg.grounds.scene.editor.paper.command

import java.util.Locale

/** Pure command-shape dispatcher. Bukkit and common services consume only this result. */
object SceneCommandDispatcher {
    fun route(args: List<String>): Route =
        when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "catalogs" ->
                if (args.size == 2 && args.getOrNull(1)?.lowercase(Locale.ROOT) == "status")
                    Route.CatalogStatus
                else Route.Invalid
            "create" -> if (args.size >= 3) Route.Create else Route.Invalid
            "info" -> if (args.size == 1) Route.Info else Route.Invalid
            "validate" -> if (args.size == 1) Route.Validate else Route.Invalid
            "save" -> if (args.size == 1) Route.Save else Route.Invalid
            "reload" -> if (args.size == 1) Route.Reload else Route.Invalid
            "history" -> if (args.size == 1) Route.History else Route.Invalid
            "undo" -> if (args.size in 1..2) Route.Undo else Route.Invalid
            "redo" -> if (args.size in 1..2) Route.Redo else Route.Invalid
            "tool" ->
                if (args.size == 2 && args.getOrNull(1)?.lowercase(Locale.ROOT) == "give")
                    Route.ToolGive
                else Route.Invalid
            "recovery" ->
                when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                    "backup-and-create" ->
                        if (args.size >= 4) Route.RecoveryBackup else Route.Invalid
                    "export" -> if (args.size == 2) Route.RecoveryExport else Route.Invalid
                    "discard-and-reload" ->
                        if (args.size == 3) Route.RecoveryDiscard(args[2] == "confirm")
                        else Route.Invalid
                    else -> Route.Invalid
                }
            "lease" ->
                when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                    "status" -> if (args.size == 3) Route.LeaseStatus else Route.Invalid
                    "release" -> if (args.size == 3) Route.LeaseRelease else Route.Invalid
                    "override" -> if (args.size == 3) Route.LeaseOverride else Route.Invalid
                    else -> Route.Invalid
                }
            "prop",
            "npc" -> element(args)
            else -> Route.Invalid
        }

    private fun element(args: List<String>): Route {
        val kind = args[0].lowercase(Locale.ROOT)
        if (args.getOrNull(1)?.lowercase(Locale.ROOT) == "list")
            return if (
                args.size in 2..3 && args.getOrNull(2)?.toIntOrNull()?.let { it > 0 } != false
            )
                Route.Element(kind, "list")
            else Route.Invalid
        val operation = args.getOrNull(2)?.lowercase(Locale.ROOT) ?: return Route.Invalid
        val valid =
            when (operation) {
                "create",
                "clone" -> args.size == 4
                "select",
                "remove" -> args.size == 3
                "position" ->
                    args.getOrNull(3)?.lowercase(Locale.ROOT).let { mode ->
                        (mode == "here" && args.size == 4) ||
                            (mode in setOf("set", "add") && args.size == 7)
                    }
                "rotation" ->
                    args.getOrNull(3)?.lowercase(Locale.ROOT) in setOf("set", "add") &&
                        args.size == 7
                "scale" -> args.getOrNull(3)?.lowercase(Locale.ROOT) == "set" && args.size == 5
                "label" ->
                    kind == "npc" &&
                        args.getOrNull(3)?.lowercase(Locale.ROOT) == "set" &&
                        args.size >= 5
                else -> false
            }
        return if (valid) Route.Element(kind, operation) else Route.Invalid
    }

    sealed interface Route {
        data object CatalogStatus : Route

        data object Create : Route

        data object Info : Route

        data object Validate : Route

        data object Save : Route

        data object Reload : Route

        data object History : Route

        data object Undo : Route

        data object Redo : Route

        data object ToolGive : Route

        data object RecoveryBackup : Route

        data object RecoveryExport : Route

        data class RecoveryDiscard(val confirmed: Boolean) : Route

        data object LeaseStatus : Route

        data object LeaseRelease : Route

        data object LeaseOverride : Route

        data class Element(val kind: String, val operation: String) : Route

        data object Invalid : Route
    }
}
