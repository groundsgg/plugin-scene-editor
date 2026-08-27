package gg.grounds.scene.editor.paper.command

/** Bukkit-free parsing and deterministic presentation helpers for the command adapter. */
object SceneCommandParser {
    fun finite(value: String?): Double? = value?.toDoubleOrNull()?.takeIf(Double::isFinite)

    fun <T> page(entries: List<T>, requestedPage: Int, pageSize: Int = 20): Page<T> {
        require(pageSize > 0)
        val pages = maxOf(1, (entries.size + pageSize - 1) / pageSize)
        val page = requestedPage.coerceIn(1, pages)
        return Page(entries.drop((page - 1) * pageSize).take(pageSize), page, pages)
    }

    data class Page<T>(val entries: List<T>, val page: Int, val pages: Int)
}
