package gg.grounds.scene.editor.paper.preview

import java.util.UUID

/** Owns per-viewer handles and applies descriptor changes without leaking displaced previews. */
class PreviewRegistry<D, H> {
    private val states = mutableMapOf<Key, State<D, H>>()
    private val orphaned = mutableMapOf<Key, MutableList<H>>()

    fun reconcile(
        worldId: UUID,
        generation: Long,
        viewerId: UUID,
        desired: Map<String, D>,
        create: (D) -> H,
        remove: (H) -> Unit,
    ): ReconcileResult {
        val key = Key(worldId, viewerId)
        cleanup(key, emptyList(), remove)
        val current = states[key]
        if (current != null && generation < current.generation) return ReconcileResult.STALE
        if (current != null && generation == current.generation && current.descriptors == desired)
            return ReconcileResult.UNCHANGED

        val retained = linkedMapOf<String, H>()
        val created = mutableListOf<H>()
        try {
            desired.forEach { (id, descriptor) ->
                val oldDescriptor = current?.descriptors?.get(id)
                val oldHandle = current?.handles?.get(id)
                retained[id] =
                    if (oldHandle != null && oldDescriptor == descriptor) oldHandle
                    else create(descriptor).also(created::add)
            }
        } catch (failure: Throwable) {
            cleanup(key, created, remove)
            throw failure
        }
        states[key] = State(generation, desired.toMap(), retained.toMap())
        cleanup(
            key,
            current?.handles?.filter { (id, handle) -> retained[id] !== handle }?.values.orEmpty(),
            remove,
        )
        return ReconcileResult.APPLIED
    }

    fun get(worldId: UUID, viewerId: UUID, elementId: String): H? =
        states[Key(worldId, viewerId)]?.handles?.get(elementId)

    fun entries(worldId: UUID, viewerId: UUID): Map<String, H> =
        states[Key(worldId, viewerId)]?.handles.orEmpty()

    fun clearViewer(worldId: UUID, viewerId: UUID, remove: (H) -> Unit): Int =
        Key(worldId, viewerId).let { key ->
            cleanup(key, states.remove(key)?.handles?.values.orEmpty(), remove)
        }

    fun clearViewer(viewerId: UUID, remove: (H) -> Unit): Int {
        val removed = states.filterKeys { it.viewerId == viewerId }
        states.keys.removeAll(removed.keys)
        val keys = (removed.keys + orphaned.keys.filter { it.viewerId == viewerId }).distinct()
        return keys.sumOf { key -> cleanup(key, removed[key]?.handles?.values.orEmpty(), remove) }
    }

    fun clearWorld(worldId: UUID, remove: (H) -> Unit): Int {
        val removed = states.filterKeys { it.worldId == worldId }
        states.keys.removeAll(removed.keys)
        val keys = (removed.keys + orphaned.keys.filter { it.worldId == worldId }).distinct()
        return keys.sumOf { key -> cleanup(key, removed[key]?.handles?.values.orEmpty(), remove) }
    }

    fun clearAll(remove: (H) -> Unit): Int {
        val removed = states.toMap()
        states.clear()
        val keys = (removed.keys + orphaned.keys).distinct()
        return keys.sumOf { key -> cleanup(key, removed[key]?.handles?.values.orEmpty(), remove) }
    }

    /** Failed removals remain registry-owned and are retried on the next operation. */
    private fun cleanup(key: Key, handles: Collection<H>, remove: (H) -> Unit): Int {
        val pending = orphaned.remove(key).orEmpty() + handles
        var removed = 0
        pending.forEach { handle ->
            try {
                remove(handle)
                removed++
            } catch (_: Throwable) {
                orphaned.getOrPut(key, ::mutableListOf) += handle
            }
        }
        return removed
    }

    private data class Key(val worldId: UUID, val viewerId: UUID)

    private data class State<D, H>(
        val generation: Long,
        val descriptors: Map<String, D>,
        val handles: Map<String, H>,
    )
}

enum class ReconcileResult {
    APPLIED,
    UNCHANGED,
    STALE,
}
