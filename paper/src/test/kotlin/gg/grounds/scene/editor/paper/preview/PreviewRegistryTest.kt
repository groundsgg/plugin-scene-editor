package gg.grounds.scene.editor.paper.preview

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewRegistryTest {
    private val world = UUID.randomUUID()
    private val firstViewer = UUID.randomUUID()
    private val secondViewer = UUID.randomUUID()

    @Test
    fun `viewer overlays are isolated and idempotent`() {
        val registry = PreviewRegistry<String, String>()
        val removed = mutableListOf<String>()

        assertEquals(
            ReconcileResult.APPLIED,
            registry.reconcile(
                world,
                1,
                firstViewer,
                mapOf("marker" to "first"),
                { it },
                removed::add,
            ),
        )
        registry.reconcile(
            world,
            1,
            secondViewer,
            mapOf("marker" to "second"),
            { it },
            removed::add,
        )
        assertEquals(
            ReconcileResult.UNCHANGED,
            registry.reconcile(
                world,
                1,
                firstViewer,
                mapOf("marker" to "first"),
                { error("create") },
                removed::add,
            ),
        )

        assertEquals("first", registry.get(world, firstViewer, "marker"))
        assertEquals("second", registry.get(world, secondViewer, "marker"))
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `stale generations cannot replace newer previews`() {
        val registry = PreviewRegistry<String, String>()
        registry.reconcile(world, 5, firstViewer, mapOf("marker" to "new"), { it }, {})

        assertEquals(
            ReconcileResult.STALE,
            registry.reconcile(world, 4, firstViewer, mapOf("marker" to "old"), { it }, {}),
        )
        assertEquals("new", registry.get(world, firstViewer, "marker"))
    }

    @Test
    fun `changed descriptors displace handles while stable entries are retained`() {
        val registry = PreviewRegistry<String, String>()
        val removed = mutableListOf<String>()
        registry.reconcile(
            world,
            1,
            firstViewer,
            mapOf("a" to "old", "b" to "stable"),
            { it },
            removed::add,
        )

        registry.reconcile(
            world,
            2,
            firstViewer,
            mapOf("a" to "new", "b" to "stable"),
            { it },
            removed::add,
        )

        assertEquals(listOf("old"), removed)
        assertEquals(mapOf("a" to "new", "b" to "stable"), registry.entries(world, firstViewer))
    }

    @Test
    fun `world cleanup removes duplicate element handles owned by different viewers`() {
        val registry = PreviewRegistry<String, String>()
        val removed = mutableListOf<String>()
        registry.reconcile(world, 1, firstViewer, mapOf("marker" to "first"), { it }, {})
        registry.reconcile(world, 1, secondViewer, mapOf("marker" to "second"), { it }, {})

        assertEquals(2, registry.clearWorld(world, removed::add))
        assertEquals(listOf("first", "second"), removed)
        assertTrue(registry.entries(world, secondViewer).isEmpty())
    }

    @Test
    fun `failed reconcile rolls back newly created handles and retains the old generation`() {
        val registry = PreviewRegistry<String, String>()
        val removed = mutableListOf<String>()
        registry.reconcile(world, 1, firstViewer, mapOf("a" to "old"), { it }, removed::add)

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            registry.reconcile(
                world,
                2,
                firstViewer,
                linkedMapOf("a" to "new", "b" to "boom"),
                { if (it == "boom") error("create failed") else it },
                removed::add,
            )
        }

        assertEquals(listOf("new"), removed)
        assertEquals("old", registry.get(world, firstViewer, "a"))
    }

    @Test
    fun `failed removals stay owned and are retried without blocking other cleanup`() {
        val registry = PreviewRegistry<String, String>()
        val attempts = mutableListOf<String>()
        var fail = true
        registry.reconcile(world, 1, firstViewer, mapOf("a" to "a", "b" to "b"), { it }, {})

        assertEquals(
            1,
            registry.clearViewer(world, firstViewer) {
                attempts += it
                if (it == "a" && fail) error("temporary remove failure")
            },
        )
        fail = false

        assertEquals(1, registry.clearAll(attempts::add))
        assertEquals(listOf("a", "b", "a"), attempts)
    }

    @Test
    fun `viewer cleanup spans worlds and clear all removes the remainder`() {
        val otherWorld = UUID.randomUUID()
        val registry = PreviewRegistry<String, String>()
        val removed = mutableListOf<String>()
        registry.reconcile(world, 1, firstViewer, mapOf("a" to "a"), { it }, {})
        registry.reconcile(otherWorld, 1, firstViewer, mapOf("b" to "b"), { it }, {})
        registry.reconcile(otherWorld, 1, secondViewer, mapOf("c" to "c"), { it }, {})

        assertEquals(2, registry.clearViewer(firstViewer, removed::add))
        assertEquals(1, registry.clearAll(removed::add))
        assertEquals(listOf("a", "b", "c"), removed)
    }

    @Test
    fun `failed cleanup is retried only by its owner scope or clear all`() {
        val otherWorld = UUID.randomUUID()
        val registry = PreviewRegistry<String, String>()
        val attempts = mutableListOf<String>()
        registry.reconcile(world, 1, firstViewer, mapOf("a" to "a"), { it }, {})
        registry.clearViewer(world, firstViewer) {
            attempts += it
            error("temporary")
        }
        registry.reconcile(otherWorld, 1, secondViewer, mapOf("b" to "b"), { it }, {})

        registry.clearWorld(otherWorld, attempts::add)
        assertEquals(listOf("a", "b"), attempts)

        registry.clearAll(attempts::add)
        assertEquals(listOf("a", "b", "a"), attempts)
    }
}
