package gg.grounds.scene.editor.lease

import gg.grounds.scene.format.LocalId
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ElementLeaseRegistryTest {
    private val world = UUID(1, 1)
    private val alice = UUID(2, 2)
    private val bob = UUID(3, 3)
    private val element = LocalId("marker")

    @Test
    fun `acquire refuses another owner until expiry and renew extends exactly two minutes`() {
        val time = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val leases = ElementLeaseRegistry(time)
        assertTrue(leases.acquire(world, element, alice) is LeaseAcquisition.Acquired)
        assertEquals(alice, (leases.acquire(world, element, bob) as LeaseAcquisition.Refused).owner)
        time.advanceSeconds(119)
        assertTrue(leases.renew(world, element, alice))
        time.advanceSeconds(119)
        assertEquals(alice, (leases.acquire(world, element, bob) as LeaseAcquisition.Refused).owner)
        time.advanceSeconds(1)
        assertTrue(leases.acquire(world, element, bob) is LeaseAcquisition.Acquired)
    }

    @Test
    fun `override transfers ownership and player releases cover disconnect world change deselect and delete`() {
        val leases = ElementLeaseRegistry(MutableClock(Instant.EPOCH))
        leases.acquire(world, element, alice)
        assertEquals(alice, leases.override(world, element, bob).previousOwner)
        assertTrue(leases.release(world, element, bob))
        leases.acquire(world, element, alice)
        leases.releasePlayer(world, alice)
        assertFalse(leases.owner(world, element).isPresent)
    }

    @Test
    fun `lease records exact acquisition and activity times and rejects nonpositive duration`() {
        val time = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val leases = ElementLeaseRegistry(time)
        val acquired = (leases.acquire(world, element, alice) as LeaseAcquisition.Acquired).lease
        assertEquals(time.instant(), acquired.acquiredAt)
        assertEquals(time.instant(), acquired.lastActivityAt)
        time.advanceSeconds(4)
        leases.renew(world, element, alice)
        val renewed = leases.lease(world, element).get()
        assertEquals(acquired.acquiredAt, renewed.acquiredAt)
        assertEquals(time.instant(), renewed.lastActivityAt)
        assertThrows(IllegalArgumentException::class.java) {
            ElementLeaseRegistry(time, java.time.Duration.ZERO)
        }
    }

    @Test
    fun `same owner acquire renews without changing acquisition time`() {
        val time = MutableClock(Instant.EPOCH)
        val leases = ElementLeaseRegistry(time)
        val first = (leases.acquire(world, element, alice) as LeaseAcquisition.Acquired).lease
        time.advanceSeconds(5)
        val renewed = (leases.acquire(world, element, alice) as LeaseAcquisition.Acquired).lease
        assertEquals(first.acquiredAt, renewed.acquiredAt)
        assertEquals(time.instant(), renewed.lastActivityAt)
    }

    private class MutableClock(private var instant: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = instant

        fun advanceSeconds(seconds: Long) {
            instant = instant.plusSeconds(seconds)
        }
    }
}
