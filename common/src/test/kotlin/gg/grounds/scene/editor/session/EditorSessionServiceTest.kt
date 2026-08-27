package gg.grounds.scene.editor.session

import gg.grounds.scene.editor.SceneEditorEvent
import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.lease.ElementLeaseRegistry
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutationRejection
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetDefinition
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import java.time.Clock
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorSessionServiceTest {
    private val world = UUID(1, 1)
    private val alice = UUID(2, 2)
    private val bob = UUID(3, 3)
    private val binding =
        SceneCatalogBinding(
            AssetCatalog(
                CatalogId("grounds:assets"),
                "1",
                CatalogVersionRange(CatalogId("grounds:resourcepacks"), "1", "1"),
                mapOf(
                    AssetKey("grounds:editor/marker") to
                        AssetDefinition(
                            AssetKey("grounds:editor/marker"),
                            AssetKind.PROP,
                            emptySet(),
                            null,
                            emptyMap(),
                        )
                ),
            ),
            gg.grounds.scene.format.ActionCatalog(CatalogId("grounds:actions"), "1", emptyMap()),
        )

    @Test
    fun `one world has one shared session but each player owns an independent selection`() {
        val service = EditorSessionService(binding)
        val session =
            (service.open(world, binding.newDocument("grounds:test")) as SessionOpenResult.Opened)
                .session
        assertSame(
            session,
            (service.open(world, binding.newDocument("grounds:ignored"))
                    as SessionOpenResult.Opened)
                .session,
        )
        service.mutate(world, createMarker(alice))
        service.select(world, alice, LocalId("marker"))
        service.mutate(world, SceneMutations.clone(alice, LocalId("marker"), LocalId("other")))
        service.select(world, alice, LocalId("other"))
        service.select(world, bob, LocalId("marker"))
        assertEquals(LocalId("other"), service.selection(world, alice)?.elementId)
        assertEquals(LocalId("marker"), service.selection(world, bob)?.elementId)
    }

    @Test
    fun `accepted mutation emits one event records an immutable history snapshot and makes status dirty`() {
        val service = EditorSessionService(binding)
        val original = binding.newDocument("grounds:test")
        service.open(world, original)
        val events = mutableListOf<SceneEditorEvent>()
        service.addListener(events::add)

        val result = service.mutate(world, createMarker(alice))

        assertTrue(result.accepted)
        assertEquals(1, events.size)
        assertEquals(1, service.session(world)!!.history.undoSize)
        assertNotSame(original, service.session(world)!!.document)
        assertTrue(service.hasUnsavedChanges(world))
    }

    @Test
    fun `rejected mutation emits nothing and preserves history and document identity`() {
        val service = EditorSessionService(binding)
        val original = binding.newDocument("grounds:test")
        service.open(world, original)
        val events = mutableListOf<SceneEditorEvent>()
        service.addListener(events::add)

        val result =
            service.mutate(
                world,
                SceneMutations.setPosition(alice, LocalId("missing"), Vec3(1.0, 0.0, 0.0)),
            )

        assertFalse(result.accepted)
        assertTrue(events.isEmpty())
        assertSame(original, service.session(world)!!.document)
        assertEquals(0, service.session(world)!!.history.undoSize)
    }

    @Test
    fun `canonical base bytes determine clean dirty state and saved history remains undoable`() {
        val service = EditorSessionService(binding)
        val original = binding.newDocument("grounds:test")
        service.open(world, original)
        service.mutate(world, createMarker(alice))
        val edited = service.session(world)!!.document
        assertTrue(service.confirmPersisted(service.prepareSave(world).snapshot()))

        assertFalse(service.hasUnsavedChanges(world))
        assertEquals(1, service.session(world)!!.history.undoSize)
        assertTrue(service.undo(world))
        assertTrue(service.hasUnsavedChanges(world))
    }

    @Test
    fun `undo redo atomically restores complete snapshots and a new edit clears redo`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        service.select(world, alice, LocalId("marker"))
        val afterFirst = service.session(world)!!.document
        service.select(world, alice, LocalId("marker"))
        service.mutate(
            world,
            SceneMutations.setPosition(alice, LocalId("marker"), Vec3(1.0, 2.0, 3.0)),
        )
        assertTrue(service.undo(world))
        assertEquals(afterFirst, service.session(world)!!.document)
        assertTrue(service.redo(world))
        assertTrue(service.undo(world))
        service.mutate(
            world,
            SceneMutations.setPosition(alice, LocalId("marker"), Vec3(9.0, 9.0, 9.0)),
        )
        assertFalse(service.redo(world))
    }

    @Test
    fun `history retains exactly one hundred pre-edit snapshots`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        service.select(world, alice, LocalId("marker"))
        repeat(101) { index ->
            service.mutate(
                world,
                SceneMutations.setPosition(
                    alice,
                    LocalId("marker"),
                    Vec3(index.toDouble(), 0.0, 0.0),
                ),
            )
        }
        assertEquals(100, service.session(world)!!.history.undoSize)
    }

    @Test
    fun `deselect releases the selected element lease for lifecycle adapters`() {
        val leases = ElementLeaseRegistry(Clock.systemUTC())
        val service = EditorSessionService(binding, leases)
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        service.select(world, alice, LocalId("marker"))
        service.deselect(world, alice)
        assertFalse(leases.owner(world, LocalId("marker")).isPresent)
    }

    @Test
    fun `targeted mutations require matching selection and owned lease while document mutations are lease free`() {
        val leases = ElementLeaseRegistry(Clock.systemUTC())
        val service = EditorSessionService(binding, leases)
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        val original = service.session(world)!!.document
        val rejected =
            service.mutate(
                world,
                SceneMutations.setPosition(alice, LocalId("marker"), Vec3(2.0, 0.0, 0.0)),
            )
        assertEquals(
            SceneMutationRejection.SELECTION_REQUIRED,
            (rejected.result as gg.grounds.scene.editor.mutation.SceneMutationResult.Rejected)
                .reason,
        )
        assertSame(original, service.session(world)!!.document)
        assertTrue(service.select(world, alice, LocalId("marker")) is SelectionResult.Selected)
        assertTrue(
            service
                .mutate(
                    world,
                    SceneMutations.setPosition(alice, LocalId("marker"), Vec3(2.0, 0.0, 0.0)),
                )
                .accepted
        )
    }

    @Test
    fun `selection acquires before switching then releases previous and override exposes displaced owner`() {
        val leases = ElementLeaseRegistry(Clock.systemUTC())
        val service = EditorSessionService(binding, leases)
        val two = binding.newDocument("grounds:test", elements = listOf())
        service.open(world, two)
        service.mutate(world, createMarker(alice))
        service.select(world, alice, LocalId("marker"))
        service.mutate(world, SceneMutations.clone(alice, LocalId("marker"), LocalId("other")))
        assertTrue(service.select(world, alice, LocalId("marker")) is SelectionResult.Selected)
        assertTrue(service.select(world, alice, LocalId("other")) is SelectionResult.Selected)
        assertFalse(leases.owner(world, LocalId("marker")).isPresent)
        assertEquals(
            alice,
            (service.select(world, bob, LocalId("other")) as SelectionResult.Refused).owner,
        )
        assertEquals(
            alice,
            (service.overrideSelection(world, bob, LocalId("other")) as SelectionResult.Selected)
                .previousOwner,
        )
        assertEquals(null, service.selection(world, alice))
    }

    @Test
    fun `multi step history preflights atomically and a stale save snapshot cannot clean newer content`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        val afterFirst = service.session(world)!!.document
        service.select(world, alice, LocalId("marker"))
        service.mutate(
            world,
            SceneMutations.setPosition(alice, LocalId("marker"), Vec3(1.0, 0.0, 0.0)),
        )
        val beforeRejectedUndo = service.session(world)!!.document
        assertFalse(service.undo(world, 3))
        assertSame(beforeRejectedUndo, service.session(world)!!.document)
        assertTrue(service.undo(world, 2))
        assertEquals(0, service.session(world)!!.document.elements.size)
        assertTrue(service.redo(world, 2))
        assertEquals(beforeRejectedUndo, service.session(world)!!.document)

        val snapshot = service.prepareSave(world).snapshot()
        service.mutate(
            world,
            SceneMutations.setPosition(alice, LocalId("marker"), Vec3(2.0, 0.0, 0.0)),
        )
        assertFalse(service.confirmPersisted(snapshot))
        assertTrue(service.hasUnsavedChanges(world))
        assertTrue(service.undo(world))
        assertEquals(afterFirst.elements.size, service.session(world)!!.document.elements.size)
    }

    @Test
    fun `each document transition advances generation and refreshes validation and save eligibility`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        val initial = service.session(world)!!.state
        service.mutate(world, createMarker(alice))
        val changed = service.session(world)!!.state
        assertEquals(initial.generation + 1, changed.generation)
        assertNotSame(initial.validation, changed.validation)
        assertEquals(changed.validation.catalogs.isVerified, changed.saveEligibility.isEligible)
        assertTrue(service.undo(world))
        assertEquals(changed.generation + 1, service.session(world)!!.state.generation)
    }

    @Test
    fun `save snapshot bytes are defensive and a corrupted public copy cannot affect confirmation`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        val snapshot = service.prepareSave(world).snapshot()
        snapshot.canonicalBytes[0] = (snapshot.canonicalBytes[0].toInt() xor 1).toByte()
        assertTrue(service.confirmPersisted(snapshot))
        assertFalse(service.hasUnsavedChanges(world))
    }

    @Test
    fun `listener errors are reported outside the monitor and reentrant events remain FIFO`() {
        val errors = mutableListOf<Throwable>()
        val service = EditorSessionService(binding, eventErrorHandler = errors::add)
        service.open(world, binding.newDocument("grounds:test"))
        val order = mutableListOf<String>()
        service.addListener {
            order += it.name
            if (order.size == 1)
                service.mutate(
                    world,
                    SceneMutations.createProp(
                        alice,
                        LocalId("second"),
                        AssetKey("grounds:editor/marker"),
                        PlayerPlacement(Vec3(1.0, 0.0, 0.0), 0.0),
                    ),
                )
        }
        service.addListener { throw IllegalStateException("listener") }
        val result = service.mutate(world, createMarker(alice))
        assertTrue(result.accepted)
        assertEquals(listOf("prop.create", "prop.create"), order)
        assertEquals(2, errors.size)
    }

    @Test
    fun `nonpositive history steps and absent sessions are controlled rejections without state changes`() {
        val service = EditorSessionService(binding)
        assertTrue(
            service.mutate(world, createMarker(alice))
                is EditorSessionService.MutationOutcome.NoSession
        )
        service.open(world, binding.newDocument("grounds:test"))
        val original = service.session(world)!!.document
        assertFalse(service.undo(world, 0))
        assertFalse(service.redo(world, -1))
        assertSame(original, service.session(world)!!.document)
    }

    private fun createMarker(actor: UUID) =
        SceneMutations.createProp(
            actor,
            LocalId("marker"),
            AssetKey("grounds:editor/marker"),
            PlayerPlacement(Vec3(0.0, 0.0, 0.0), 0.0),
        )

    private fun SaveSnapshotResult.snapshot(): SaveSnapshot =
        (this as SaveSnapshotResult.Prepared).snapshot
}
