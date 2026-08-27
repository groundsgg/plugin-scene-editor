package gg.grounds.scene.editor.paper.command

import java.util.Locale
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class SceneTabCompleter(
    private val assets: (Boolean) -> List<String>,
    private val elementIds: (CommandSender?, Boolean) -> List<String>,
) : TabCompleter {
    constructor(assets: (Boolean) -> List<String>) : this(assets, { _, _ -> emptyList() })

    constructor(scene: SceneCommand) : this(scene::catalogAssets, scene::elementIds)

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<String>,
    ): List<String> = complete(sender, args)

    fun complete(sender: CommandSender?, args: Array<String>): List<String> {
        val candidates =
            when (args.size) {
                0,
                1 ->
                    listOf(
                        "create",
                        "info",
                        "validate",
                        "save",
                        "reload",
                        "history",
                        "undo",
                        "redo",
                        "recovery",
                        "tool",
                        "prop",
                        "npc",
                        "lease",
                        "catalogs",
                    )
                2 ->
                    when (args[0].lowercase(Locale.ROOT)) {
                        "prop" -> listOf("list") + elementIds(sender, false)
                        "npc" -> listOf("list") + elementIds(sender, true)
                        "recovery" -> listOf("backup-and-create", "export", "discard-and-reload")
                        "lease" -> listOf("status", "release", "override")
                        "catalogs" -> listOf("status")
                        "tool" -> listOf("give")
                        else -> emptyList()
                    }
                3 ->
                    if (args[1].equals("list", true)) listOf("1")
                    else
                        when (args[0].lowercase(Locale.ROOT)) {
                            "prop",
                            "npc" ->
                                listOf(
                                    "create",
                                    "select",
                                    "position",
                                    "rotation",
                                    "scale",
                                    "clone",
                                    "remove",
                                ) +
                                    if (args[0].equals("npc", true)) listOf("label")
                                    else emptyList()
                            else -> emptyList()
                        }
                4 ->
                    when {
                        args[0].equals("prop", true) && args[2].equals("create", true) ->
                            assets(false)
                        args[0].equals("npc", true) && args[2].equals("create", true) ->
                            assets(true)
                        args[2].equals("position", true) -> listOf("set", "here", "add")
                        args[2].equals("rotation", true) -> listOf("set", "add")
                        args[2].equals("scale", true) -> listOf("set")
                        args[2].equals("label", true) -> listOf("set")
                        else -> emptyList()
                    }
                else -> emptyList()
            }
        val needle = args.lastOrNull().orEmpty().lowercase(Locale.ROOT)
        return candidates
            .filter { it.lowercase(Locale.ROOT).startsWith(needle) }
            .filter { candidate ->
                sender == null ||
                    SceneCommandAuthorizer(sender::hasPermission)
                        .isAllowed(permissionPath(args, candidate))
            }
            .sorted()
    }

    private fun permissionPath(args: Array<String>, candidate: String): List<String> =
        (if (
                args.size == 2 && candidate != "list" && args[0].equals("prop", true) ||
                    args.size == 2 && candidate != "list" && args[0].equals("npc", true)
            )
                listOf(args[0], candidate, "select")
            else if (args.size <= 1) listOf(candidate) else args.dropLast(1) + candidate)
            .map { it.lowercase(Locale.ROOT) }
}
