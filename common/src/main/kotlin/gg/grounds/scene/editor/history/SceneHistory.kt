package gg.grounds.scene.editor.history

import gg.grounds.scene.format.SceneDocument

/**
 * Complete document snapshots; lists are copied on each transition and therefore never shared
 * mutable state.
 */
class SceneHistory
private constructor(private val undo: List<SceneDocument>, private val redo: List<SceneDocument>) {
    val undoSize: Int
        get() = undo.size

    val redoSize: Int
        get() = redo.size

    fun record(before: SceneDocument): SceneHistory =
        SceneHistory((undo + before).takeLast(MAX_SNAPSHOTS), emptyList())

    fun undo(current: SceneDocument): Transition? {
        val previous = undo.lastOrNull() ?: return null
        return Transition(previous, SceneHistory(undo.dropLast(1), redo + current))
    }

    fun redo(current: SceneDocument): Transition? {
        val next = redo.lastOrNull() ?: return null
        return Transition(next, SceneHistory(undo + current, redo.dropLast(1)))
    }

    fun undo(current: SceneDocument, steps: Int): Transition? =
        move(current, steps, undo, redo, true)

    fun redo(current: SceneDocument, steps: Int): Transition? =
        move(current, steps, redo, undo, false)

    data class Transition(val document: SceneDocument, val history: SceneHistory)

    companion object {
        const val MAX_SNAPSHOTS = 100

        fun empty(): SceneHistory = SceneHistory(emptyList(), emptyList())
    }

    private fun move(
        current: SceneDocument,
        steps: Int,
        source: List<SceneDocument>,
        destination: List<SceneDocument>,
        undoDirection: Boolean,
    ): Transition? {
        require(steps > 0) { "steps must be positive" }
        if (source.size < steps) return null
        var document = current
        var remaining = source
        var moved = destination
        repeat(steps) {
            val next = remaining.last()
            remaining = remaining.dropLast(1)
            moved = moved + document
            document = next
        }
        val history =
            if (undoDirection) SceneHistory(remaining, moved) else SceneHistory(moved, remaining)
        return Transition(document, history)
    }
}
