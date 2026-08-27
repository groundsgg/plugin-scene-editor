package gg.grounds.scene.editor.lease

import gg.grounds.scene.format.LocalId
import java.time.Clock
import java.time.Duration
import java.util.Optional
import java.util.UUID

/** Thread-safe element-only lease registry. Document operations deliberately do not consult it. */
class ElementLeaseRegistry(
    private val clock: Clock,
    private val duration: Duration = Duration.ofSeconds(120),
) {
    init {
        require(!duration.isZero && !duration.isNegative) { "Lease duration must be positive" }
    }

    private val leases = linkedMapOf<Pair<UUID, LocalId>, ElementLease>()

    @Synchronized
    fun acquire(worldId: UUID, elementId: LocalId, owner: UUID): LeaseAcquisition {
        val key = worldId to elementId
        val existing = active(key)
        if (existing != null && existing.owner != owner)
            return LeaseAcquisition.Refused(existing.owner, existing.expiresAt)
        if (existing != null) {
            renew(worldId, elementId, owner)
            return LeaseAcquisition.Acquired(requireNotNull(leases[key]))
        }
        val now = clock.instant()
        val lease = ElementLease(worldId, elementId, owner, now, now, now.plus(duration))
        leases[key] = lease
        return LeaseAcquisition.Acquired(lease)
    }

    @Synchronized
    fun renew(worldId: UUID, elementId: LocalId, owner: UUID): Boolean {
        val key = worldId to elementId
        val current = active(key) ?: return false
        if (current.owner != owner) return false
        val now = clock.instant()
        leases[key] = current.copy(lastActivityAt = now, expiresAt = now.plus(duration))
        return true
    }

    @Synchronized
    fun override(worldId: UUID, elementId: LocalId, owner: UUID): LeaseOverride {
        val previous = active(worldId to elementId)?.owner
        val now = clock.instant()
        val lease = ElementLease(worldId, elementId, owner, now, now, now.plus(duration))
        leases[worldId to elementId] = lease
        return LeaseOverride(lease, previous)
    }

    @Synchronized
    fun owner(worldId: UUID, elementId: LocalId): Optional<UUID> =
        Optional.ofNullable(active(worldId to elementId)?.owner)

    @Synchronized
    fun lease(worldId: UUID, elementId: LocalId): Optional<ElementLease> =
        Optional.ofNullable(active(worldId to elementId))

    @Synchronized
    fun release(worldId: UUID, elementId: LocalId, owner: UUID): Boolean {
        val key = worldId to elementId
        val current = active(key) ?: return false
        if (current.owner != owner) return false
        leases.remove(key)
        return true
    }

    @Synchronized
    fun releasePlayer(worldId: UUID, owner: UUID) {
        leases.entries.removeIf { (key, lease) -> key.first == worldId && lease.owner == owner }
    }

    @Synchronized
    fun releaseElement(worldId: UUID, elementId: LocalId) {
        leases.remove(worldId to elementId)
    }

    private fun active(key: Pair<UUID, LocalId>): ElementLease? {
        val lease = leases[key] ?: return null
        if (!clock.instant().isBefore(lease.expiresAt)) {
            leases.remove(key)
            return null
        }
        return lease
    }
}
