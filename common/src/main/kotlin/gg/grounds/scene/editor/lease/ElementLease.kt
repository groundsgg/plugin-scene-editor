package gg.grounds.scene.editor.lease

import gg.grounds.scene.format.LocalId
import java.time.Instant
import java.util.UUID

data class ElementLease(
    val worldId: UUID,
    val elementId: LocalId,
    val owner: UUID,
    val acquiredAt: Instant,
    val lastActivityAt: Instant,
    val expiresAt: Instant,
)

sealed interface LeaseAcquisition {
    data class Acquired(val lease: ElementLease) : LeaseAcquisition

    data class Refused(val owner: UUID, val expiresAt: Instant) : LeaseAcquisition
}

data class LeaseOverride(val lease: ElementLease, val previousOwner: UUID?)
