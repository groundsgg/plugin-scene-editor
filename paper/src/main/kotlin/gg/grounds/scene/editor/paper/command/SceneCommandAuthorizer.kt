package gg.grounds.scene.editor.paper.command

import java.util.Locale

/** Maps only supported first-slice command paths to their exact Bukkit permission. */
class SceneCommandAuthorizer(private val hasPermission: (String) -> Boolean) {
    fun isAllowed(path: List<String>): Boolean = permissionsFor(path).any(hasPermission)

    fun permissionsFor(path: List<String>): List<String> {
        val normalized = path.map { it.lowercase(Locale.ROOT) }
        return if (normalized.isEmpty()) listOf("grounds.scene")
        else
            when (normalized.firstOrNull()) {
                "catalogs" ->
                    if (normalized.getOrNull(1) == "status") listOf("grounds.scene.catalogs.status")
                    else emptyList()
                "save" -> listOf("grounds.scene.save")
                "recovery" ->
                    normalized
                        .getOrNull(1)
                        ?.takeIf {
                            it in setOf("backup-and-create", "export", "discard-and-reload")
                        }
                        ?.let { listOf("grounds.scene.recovery.$it", "grounds.scene.recovery") }
                        ?: emptyList()
                "lease" ->
                    when (normalized.getOrNull(1)) {
                        "status" -> listOf("grounds.scene.lease.status", "grounds.scene.edit")
                        "release" ->
                            listOf(
                                "grounds.scene.lease.release",
                                "grounds.scene.lease.override",
                                "grounds.scene.edit",
                            )
                        "override" -> listOf("grounds.scene.lease.override")
                        else -> emptyList()
                    }
                "create",
                "info",
                "validate",
                "reload",
                "history",
                "undo",
                "redo" -> listOf("grounds.scene.${normalized[0]}", "grounds.scene.edit")
                "tool" ->
                    if (normalized.getOrNull(1) == "give")
                        listOf("grounds.scene.tool.give", "grounds.scene.edit")
                    else emptyList()
                "prop",
                "npc" -> elementPermission(normalized)
                else -> emptyList()
            }
    }

    private fun elementPermission(path: List<String>): List<String> {
        val kind = path.first()
        val operation = if (path.getOrNull(1) == "list") "list" else path.getOrNull(2)
        val allowed =
            setOf("list", "create", "select", "position", "rotation", "scale", "clone", "remove") +
                if (kind == "npc") setOf("label") else emptySet()
        return operation
            ?.takeIf { it in allowed }
            ?.let { allowedOperation ->
                val modes =
                    when (allowedOperation) {
                        "position" ->
                            path.getOrNull(3)?.let(::listOf) ?: listOf("set", "here", "add")
                        "rotation" -> path.getOrNull(3)?.let(::listOf) ?: listOf("set", "add")
                        "scale",
                        "label" -> path.getOrNull(3)?.let(::listOf) ?: listOf("set")
                        else -> listOf<String?>(null)
                    }
                modes.map { mode ->
                    "grounds.scene.$kind.$allowedOperation${mode?.let { ".$it" }.orEmpty()}"
                } + "grounds.scene.edit"
            } ?: emptyList()
    }
}
