package gg.grounds.scene.editor.session

import gg.grounds.scene.editor.SceneEditorEvent
import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.lease.ElementLeaseRegistry
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutationRejection
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.editor.repository.AtomicSceneFileStore
import gg.grounds.scene.editor.repository.NioAtomicSceneFileStore
import gg.grounds.scene.editor.repository.PathRejection
import gg.grounds.scene.editor.repository.SceneFingerprint
import gg.grounds.scene.editor.repository.SceneLoadResult
import gg.grounds.scene.editor.repository.SceneSaveResult
import gg.grounds.scene.editor.repository.WorldSceneRepository
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetDefinition
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogReference
import gg.grounds.scene.format.CatalogVersionRange
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.LookBehavior
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.SceneCatalogReferences
import gg.grounds.scene.format.SceneDecodeResult
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneEncodeResult
import gg.grounds.scene.format.SceneJson
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.TriggerBinding
import gg.grounds.scene.format.Vec3
import java.nio.file.Files
import java.nio.file.Path
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
    fun `service save writes canonical root, updates fingerprint and leaves history undoable`() {
        val service = EditorSessionService(binding)
        val root = Files.createTempDirectory("scene-service-save")
        val repository = WorldSceneRepository(root)
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        assertTrue(service.save(world, repository) is SceneSaveResult.Saved)

        assertFalse(service.hasUnsavedChanges(world))
        assertTrue(repository.load() is SceneLoadResult.Loaded)
        assertEquals(
            (repository.load() as SceneLoadResult.Loaded).fingerprint,
            service.session(world)!!.state.baseFingerprint,
        )
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
    fun `multi step history preflights atomically`() {
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
    fun `normal save refuses catalog-unverified documents`() {
        val service = EditorSessionService(binding)
        val verified = binding.newDocument("grounds:test")
        val unverified =
            SceneDocument(
                verified.schemaVersion,
                verified.id,
                verified.metadata,
                SceneCatalogReferences(
                    CatalogReference(CatalogId("grounds:assets"), "other"),
                    verified.catalogs.actions,
                ),
                verified.groups,
                verified.elements,
            )
        service.open(world, unverified)

        val result =
            service.save(world, WorldSceneRepository(Files.createTempDirectory("scene-ineligible")))

        assertTrue(result is SceneSaveResult.Ineligible)
    }

    @Test
    fun `current navigator pin survives an unrelated transform edit and save decode`() {
        val catalogs = SceneCatalogBinding.production()
        val service = EditorSessionService(catalogs)
        val document = navigatorDocument(catalogs, emptyMap())
        val repository = WorldSceneRepository(Files.createTempDirectory("navigator-round-trip"))
        service.open(world, document)
        assertTrue(service.select(world, alice, LocalId("guide")) is SelectionResult.Selected)
        assertTrue(
            service
                .mutate(
                    world,
                    SceneMutations.setPosition(alice, LocalId("guide"), Vec3(4.0, 0.0, 0.0)),
                )
                .accepted
        )
        assertTrue(service.save(world, repository) is SceneSaveResult.Saved)

        val decoded = SceneJson.decode(Files.readAllBytes(repository.scenePath()))
        assertTrue(decoded is SceneDecodeResult.Success)
        val saved = (decoded as SceneDecodeResult.Success).scene
        assertEquals("2", saved.catalogs.actions.version)
        assertEquals(
            ApplicationAction(gg.grounds.lobby.scene.LobbySceneCatalogs.OPEN_NAVIGATOR, emptyMap()),
            navigatorAction(saved),
        )
    }

    @Test
    fun `legacy action pin survives open edit save and decode`() {
        val catalogs = SceneCatalogBinding.production()
        val service = EditorSessionService(catalogs)
        val fresh = catalogs.newDocument("grounds:legacy")
        val legacy =
            SceneDocument(
                fresh.schemaVersion,
                fresh.id,
                fresh.metadata,
                SceneCatalogReferences(
                    fresh.catalogs.assets,
                    CatalogReference(CatalogId("grounds:actions"), "1"),
                ),
                fresh.groups,
                fresh.elements,
            )
        val repository = WorldSceneRepository(Files.createTempDirectory("legacy-round-trip"))
        service.open(world, legacy)
        assertTrue(service.mutate(world, createMarker(alice)).accepted)
        assertTrue(service.select(world, alice, LocalId("marker")) is SelectionResult.Selected)
        assertTrue(
            service
                .mutate(
                    world,
                    SceneMutations.setPosition(alice, LocalId("marker"), Vec3(4.0, 0.0, 0.0)),
                )
                .accepted
        )
        assertTrue(service.save(world, repository) is SceneSaveResult.Saved)

        val decoded = SceneJson.decode(Files.readAllBytes(repository.scenePath()))
        assertTrue(decoded is SceneDecodeResult.Success)
        val saved = (decoded as SceneDecodeResult.Success).scene
        assertEquals("1", saved.catalogs.actions.version)
        assertTrue(catalogs.status(saved).isVerified)
        assertTrue((saved.elements.single() as gg.grounds.scene.format.Prop).initialAnimation == null)
    }

    @Test
    fun `unverified navigator arguments remain serialized through unrelated transform edit`() {
        val catalogs = SceneCatalogBinding.production()
        val arguments =
            mapOf(LocalId("unexpected") to gg.grounds.scene.format.StringArgument("value"))
        val service = EditorSessionService(catalogs)
        val document = navigatorDocument(catalogs, arguments)
        service.open(world, document)
        assertFalse(catalogs.actionsVerified(document))
        assertTrue(service.select(world, alice, LocalId("guide")) is SelectionResult.Selected)
        assertTrue(
            service
                .mutate(
                    world,
                    SceneMutations.setPosition(alice, LocalId("guide"), Vec3(4.0, 0.0, 0.0)),
                )
                .accepted
        )

        val bytes =
            (SceneJson.encode(service.session(world)!!.document) as SceneEncodeResult.Success).bytes
        val decoded = SceneJson.decode(bytes) as SceneDecodeResult.Success
        assertEquals(arguments, navigatorAction(decoded.scene).arguments)
        assertFalse(catalogs.actionsVerified(decoded.scene))
    }

    @Test
    fun `save reservation blocks transitions and nested save then releases after exception`() {
        val service = EditorSessionService(binding)
        val root = Files.createTempDirectory("scene-reservation")
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        service.select(world, alice, LocalId("marker"))
        lateinit var repository: WorldSceneRepository
        val store = CallbackStore {
            assertEquals(
                SceneMutationRejection.SAVE_IN_PROGRESS,
                (service
                        .mutate(
                            world,
                            SceneMutations.setPosition(
                                alice,
                                LocalId("marker"),
                                Vec3(3.0, 0.0, 0.0),
                            ),
                        )
                        .result as gg.grounds.scene.editor.mutation.SceneMutationResult.Rejected)
                    .reason,
            )
            assertFalse(service.undo(world))
            assertFalse(service.redo(world))
            assertTrue(service.save(world, repository) is SceneSaveResult.SaveInProgress)
            throw IllegalStateException("injected")
        }
        repository = WorldSceneRepository(root, store)

        assertTrue(service.save(world, repository) is SceneSaveResult.IoFailure)
        assertTrue(
            service
                .mutate(
                    world,
                    SceneMutations.setPosition(alice, LocalId("marker"), Vec3(4.0, 0.0, 0.0)),
                )
                .accepted
        )
    }

    @Test
    fun `fatal save error releases reservation before it is rethrown`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        val repository =
            WorldSceneRepository(
                Files.createTempDirectory("scene-fatal-reservation"),
                CallbackStore { throw AssertionError("fatal") },
            )
        try {
            service.save(world, repository)
            throw AssertionError("expected fatal error")
        } catch (error: AssertionError) {
            assertEquals("fatal", error.message)
        }
        assertTrue(
            service.save(
                world,
                WorldSceneRepository(Files.createTempDirectory("scene-after-fatal")),
            ) is SceneSaveResult.Saved
        )
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

    @Test
    fun `clean loaded replacement resets collaboration state and establishes exact persistence base`() {
        val leases = ElementLeaseRegistry(Clock.systemUTC())
        val service = EditorSessionService(binding, leases)
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        service.select(world, alice, LocalId("marker"))
        assertTrue(service.undo(world))
        val generationBefore = service.session(world)!!.state.generation
        val loadedDocument = binding.newDocument("grounds:reloaded")
        val loadedBytes = gg.grounds.scene.format.SceneJson.encode(loadedDocument)
        val bytes = (loadedBytes as gg.grounds.scene.format.SceneEncodeResult.Success).bytes
        val loaded =
            SceneLoadResult.Loaded(
                loadedDocument,
                SceneFingerprint.of(bytes) as SceneFingerprint.Present,
                bytes,
            )

        val result = service.replaceFromLoad(world, loaded, preparedReload(service))

        assertTrue(result is SessionReloadResult.Reloaded)
        val state = service.session(world)!!.state
        assertEquals(loadedDocument, state.document)
        assertEquals(0, state.history.undoSize)
        assertEquals(0, state.history.redoSize)
        assertEquals(null, service.selection(world, alice))
        assertFalse(leases.owner(world, LocalId("marker")).isPresent)
        assertEquals(loaded.fingerprint, state.baseFingerprint)
        assertEquals(generationBefore + 1, state.generation)
        assertTrue(state.matchesBaseCanonicalBytes(bytes))
        assertFalse(service.hasUnsavedChanges(world))
        assertTrue(state.saveEligibility.isEligible)
    }

    @Test
    fun `dirty replacement requires explicit typed discard and invalid loads stay structured`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        val loadedDocument = binding.newDocument("grounds:reloaded")
        val bytes =
            (gg.grounds.scene.format.SceneJson.encode(loadedDocument)
                    as gg.grounds.scene.format.SceneEncodeResult.Success)
                .bytes
        val loaded =
            SceneLoadResult.Loaded(
                loadedDocument,
                SceneFingerprint.of(bytes) as SceneFingerprint.Present,
                bytes,
            )

        val confirmationSnapshot = preparedReload(service)
        assertTrue(
            service.replaceFromLoad(world, loaded, confirmationSnapshot)
                is SessionReloadResult.DiscardConfirmationRequired
        )
        assertTrue(
            service.replaceFromLoad(
                world,
                loaded,
                confirmationSnapshot,
                ReloadPolicy.CONFIRMED_DISCARD,
            ) is SessionReloadResult.StaleSession
        )
        val confirmedSnapshot = preparedReload(service)
        val discarded =
            service.replaceFromLoad(
                world,
                loaded,
                confirmedSnapshot,
                ReloadPolicy.CONFIRMED_DISCARD,
            )
        assertTrue(discarded is SessionReloadResult.Reloaded)
        assertTrue((discarded as SessionReloadResult.Reloaded).discardAudit != null)
        assertTrue(
            service.replaceFromLoad(world, SceneLoadResult.Absent, preparedReload(service))
                is SessionReloadResult.LoadUnavailable
        )
        assertTrue(
            service.replaceFromLoad(
                world,
                SceneLoadResult.Rejected(PathRejection.IO_FAILURE),
                preparedReload(service),
            ) is SessionReloadResult.LoadUnavailable
        )
        assertTrue(
            service.replaceFromLoad(
                world,
                SceneLoadResult.Invalid(
                    bytes,
                    emptyList(),
                    SceneFingerprint.of(bytes) as SceneFingerprint.Present,
                ),
                preparedReload(service),
            ) is SessionReloadResult.LoadUnavailable
        )
    }

    @Test
    fun `reload refuses active save and lease status plus admin release are controlled`() {
        val leases = ElementLeaseRegistry(Clock.systemUTC())
        val service = EditorSessionService(binding, leases)
        service.open(world, binding.newDocument("grounds:test"))
        service.mutate(world, createMarker(alice))
        service.select(world, alice, LocalId("marker"))
        assertTrue(service.leaseStatus(world, LocalId("marker")) is LeaseStatusResult.Held)
        assertTrue(service.releaseLease(world, LocalId("marker")) is LeaseReleaseResult.Released)
        assertTrue(service.leaseStatus(world, LocalId("marker")) is LeaseStatusResult.Available)
        assertEquals(null, service.selection(world, alice))
        assertTrue(
            service.releaseLease(world, LocalId("missing")) is LeaseReleaseResult.ElementNotFound
        )
        assertTrue(
            service.leaseStatus(UUID(9, 9), LocalId("marker")) is LeaseStatusResult.NoSession
        )
    }

    @Test
    fun `replacement cannot invalidate an active save reservation`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        val loadedDocument = binding.newDocument("grounds:reloaded")
        val bytes =
            (gg.grounds.scene.format.SceneJson.encode(loadedDocument)
                    as gg.grounds.scene.format.SceneEncodeResult.Success)
                .bytes
        val loaded =
            SceneLoadResult.Loaded(
                loadedDocument,
                SceneFingerprint.of(bytes) as SceneFingerprint.Present,
                bytes,
            )
        var replacement: SessionReloadResult? = null
        var preparation: ReloadPreparationResult? = null
        val snapshot = preparedReload(service)
        val repository =
            WorldSceneRepository(
                Files.createTempDirectory("scene-reload-reservation"),
                CallbackStore {
                    replacement = service.replaceFromLoad(world, loaded, snapshot)
                    preparation = service.prepareReload(world)
                },
            )

        assertTrue(service.save(world, repository) is SceneSaveResult.Saved)
        assertTrue(replacement is SessionReloadResult.SaveInProgress)
        assertTrue(preparation is ReloadPreparationResult.SaveInProgress)
        assertEquals("grounds:test", service.session(world)!!.document.id.value)
    }

    @Test
    fun `reload snapshot refuses an asynchronously loaded document after a newer mutation`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        val snapshot = (service.prepareReload(world) as ReloadPreparationResult.Prepared).snapshot
        assertTrue(service.mutate(world, createMarker(alice)).accepted)
        val loaded = loaded("grounds:reloaded")

        val result =
            service.replaceFromLoad(world, loaded, snapshot, ReloadPolicy.CONFIRMED_DISCARD)

        assertTrue(result is SessionReloadResult.StaleSession)
        assertEquals("grounds:test", service.session(world)!!.document.id.value)
        assertTrue(service.session(world)!!.document.elements.any { it.id == LocalId("marker") })
    }

    @Test
    fun `reload snapshot refuses an older base after a save completes`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:test"))
        val snapshot = (service.prepareReload(world) as ReloadPreparationResult.Prepared).snapshot
        assertTrue(
            service.save(
                world,
                WorldSceneRepository(Files.createTempDirectory("reload-stale-save")),
            ) is SceneSaveResult.Saved
        )

        val result = service.replaceFromLoad(world, loaded("grounds:reloaded"), snapshot)

        assertTrue(result is SessionReloadResult.StaleSession)
        assertEquals("grounds:test", service.session(world)!!.document.id.value)
    }

    @Test
    fun `reload snapshot is bound to its owning service and exact world`() {
        val first = EditorSessionService(binding)
        val second = EditorSessionService(binding)
        val otherWorld = UUID(6, 6)
        first.open(world, binding.newDocument("grounds:first"))
        first.open(otherWorld, binding.newDocument("grounds:other"))
        second.open(world, binding.newDocument("grounds:second"))
        val firstSnapshot = preparedReload(first)

        assertTrue(
            second.replaceFromLoad(world, loaded("grounds:replacement"), firstSnapshot)
                is SessionReloadResult.StaleSession
        )
        assertTrue(
            first.replaceFromLoad(otherWorld, loaded("grounds:replacement"), firstSnapshot)
                is SessionReloadResult.StaleSession
        )
        assertEquals("grounds:first", first.session(world)!!.document.id.value)
        assertEquals("grounds:other", first.session(otherWorld)!!.document.id.value)
        assertEquals("grounds:second", second.session(world)!!.document.id.value)
    }

    private fun loaded(id: String): SceneLoadResult.Loaded {
        val document = binding.newDocument(id)
        val bytes =
            (gg.grounds.scene.format.SceneJson.encode(document)
                    as gg.grounds.scene.format.SceneEncodeResult.Success)
                .bytes
        return SceneLoadResult.Loaded(
            document,
            SceneFingerprint.of(bytes) as SceneFingerprint.Present,
            bytes,
        )
    }

    private fun navigatorDocument(
        catalogs: SceneCatalogBinding,
        arguments: Map<LocalId, gg.grounds.scene.format.ApplicationArgument>,
    ): SceneDocument {
        val base = catalogs.newDocument("grounds:navigator")
        val navigator =
            ApplicationAction(gg.grounds.lobby.scene.LobbySceneCatalogs.OPEN_NAVIGATOR, arguments)
        val npc =
            Npc(
                LocalId("guide"),
                null,
                gg.grounds.scene.format.Transform(
                    Vec3(0.0, 0.0, 0.0),
                    gg.grounds.scene.format.EulerRotation(0.0, 0.0, 0.0),
                    Vec3(1.0, 1.0, 1.0),
                ),
                body = gg.grounds.scene.format.AssetKey("grounds:editor/guide"),
                label = null,
                labelOffset = Vec3(0.0, 2.25, 0.0),
                look = LookBehavior.Fixed,
                initialAnimation = null,
                interactionBounds =
                    gg.grounds.scene.format.LocalBounds(Vec3(-0.3, 0.0, -0.3), Vec3(0.3, 1.8, 0.3)),
                proximity = null,
                bindings =
                    listOf(
                        TriggerBinding(
                            SceneTrigger.LEFT_CLICK,
                            emptyList(),
                            0,
                            0,
                            listOf(navigator),
                        )
                    ),
            )
        return SceneDocument(
            base.schemaVersion,
            base.id,
            base.metadata,
            base.catalogs,
            base.groups,
            listOf(npc),
        )
    }

    private fun navigatorAction(document: SceneDocument): ApplicationAction =
        ((document.elements.single() as Npc).bindings.single().actions.single()
            as ApplicationAction)

    private fun preparedReload(service: EditorSessionService): EditorSessionService.ReloadSnapshot =
        (service.prepareReload(world) as ReloadPreparationResult.Prepared).snapshot

    @Test
    fun `initial open accepts loaded scenes and creates an absent scene without persisting it`() {
        val service = EditorSessionService(binding)
        val loadedDocument = binding.newDocument("grounds:loaded")
        val bytes =
            (gg.grounds.scene.format.SceneJson.encode(loadedDocument)
                    as gg.grounds.scene.format.SceneEncodeResult.Success)
                .bytes
        val loaded =
            SceneLoadResult.Loaded(
                loadedDocument,
                SceneFingerprint.of(bytes) as SceneFingerprint.Present,
                bytes,
            )

        val loadedResult =
            service.openFromLoad(world, loaded, binding.newDocument("grounds:unused"))

        assertTrue(loadedResult is SessionBootstrapResult.Opened)
        assertEquals(loadedDocument, service.session(world)!!.document)
        assertEquals(loaded.fingerprint, service.session(world)!!.state.baseFingerprint)
        val absentWorld = UUID(7, 7)
        val initial = binding.newDocument("grounds:created")
        assertTrue(
            service.openFromLoad(absentWorld, SceneLoadResult.Absent, initial)
                is SessionBootstrapResult.Opened
        )
        assertEquals(SceneFingerprint.Absent, service.session(absentWorld)!!.state.baseFingerprint)
        assertFalse(service.hasUnsavedChanges(absentWorld))
    }

    @Test
    fun `lazy initial open only opens an existing loaded source and leaves absent worlds unopened`() {
        val service = EditorSessionService(binding)
        val document = binding.newDocument("grounds:lazy")
        val bytes =
            (gg.grounds.scene.format.SceneJson.encode(document)
                    as gg.grounds.scene.format.SceneEncodeResult.Success)
                .bytes
        val loaded =
            SceneLoadResult.Loaded(
                document,
                SceneFingerprint.of(bytes) as SceneFingerprint.Present,
                bytes,
            )

        assertTrue(service.openFromLoad(world, loaded) is SessionBootstrapResult.Opened)
        val absentWorld = UUID(8, 8)
        assertTrue(
            service.openFromLoad(absentWorld, SceneLoadResult.Absent)
                is SessionBootstrapResult.AbsentSource
        )
        assertEquals(null, service.session(absentWorld))
    }

    @Test
    fun `initial invalid load remains structured until common backup and create opens recovered scene`() {
        val root = Files.createTempDirectory("scene-bootstrap-invalid")
        val repository = WorldSceneRepository(root)
        val invalid = "not json".encodeToByteArray()
        root.resolve("scene.json").toFile().writeBytes(invalid)
        val invalidLoad = repository.load() as SceneLoadResult.Invalid
        val service = EditorSessionService(binding)
        val replacement = binding.newDocument("grounds:recovered")

        assertTrue(
            service.openFromLoad(world, invalidLoad, replacement)
                is SessionBootstrapResult.InvalidSource
        )
        val recovered = service.recoverInvalidAndOpen(world, invalidLoad, replacement, repository)

        assertTrue(recovered is InvalidRecoveryOpenResult.Opened)
        assertEquals(replacement, service.session(world)!!.document)
        assertFalse(service.hasUnsavedChanges(world))
        assertTrue(repository.load() is SceneLoadResult.Loaded)
        assertEquals(
            1,
            Files.list(root).use { paths ->
                paths.filter { path -> path.fileName.toString().endsWith(".bak") }.count()
            },
        )
    }

    @Test
    fun `preview snapshot is immutable and advances with document generation`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:preview"))
        val before = requireNotNull(service.previewSnapshot(world))

        assertTrue(service.mutate(world, createMarker(alice)).accepted)
        service.select(world, alice, LocalId("marker"))
        val after = requireNotNull(service.previewSnapshot(world))

        assertEquals(0, before.generation)
        assertTrue(before.document.elements.isEmpty())
        assertTrue(before.selections.isEmpty())
        assertEquals(1, after.generation)
        assertEquals(LocalId("marker"), after.selections[alice]?.elementId)
        assertEquals(setOf(world), service.openWorldIds())
    }

    @Test
    fun `closing a world reports dirty editors and revokes all ephemeral state`() {
        val service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:closing"))
        assertTrue(service.mutate(world, createMarker(alice)).accepted)
        service.select(world, alice, LocalId("marker"))
        service.deselect(world, alice)

        val result = service.closeWorld(world) as SessionCloseResult.Closed

        assertTrue(result.snapshot.dirty)
        assertEquals(setOf(alice), result.snapshot.editorPlayers)
        assertEquals(null, service.session(world))
        assertEquals(null, service.selection(world, alice))
        assertTrue(service.closeWorld(world) is SessionCloseResult.NotOpen)
    }

    @Test
    fun `closing during an active save revokes replacement before the atomic move`() {
        val root = Files.createTempDirectory("scene-close-save")
        lateinit var service: EditorSessionService
        val repository = WorldSceneRepository(root, CallbackStore { service.closeWorld(world) })
        service = EditorSessionService(binding)
        service.open(world, binding.newDocument("grounds:closing-save"))
        assertTrue(service.mutate(world, createMarker(alice)).accepted)

        val result = service.save(world, repository)

        assertTrue(result is SceneSaveResult.StaleGeneration)
        assertFalse(Files.exists(root.resolve("scene.json")))
        assertEquals(null, service.session(world))
    }

    private fun createMarker(actor: UUID) =
        SceneMutations.createProp(
            actor,
            LocalId("marker"),
            AssetKey("grounds:editor/marker"),
            PlayerPlacement(Vec3(0.0, 0.0, 0.0), 0.0),
        )

    private class CallbackStore(private val callback: () -> Unit) :
        AtomicSceneFileStore by NioAtomicSceneFileStore {
        private var called = false

        override fun writeAndFlush(path: Path, bytes: ByteArray) {
            NioAtomicSceneFileStore.writeAndFlush(path, bytes)
            if (!called) {
                called = true
                callback()
            }
        }
    }
}
