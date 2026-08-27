package gg.grounds.scene.editor.paper.command

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.paper.BuildWorldTarget
import gg.grounds.scene.editor.paper.tool.EditorTool
import gg.grounds.scene.editor.repository.WorldSceneRepository
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.editor.session.ReloadPreparationResult
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.ActionKey
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.AssetDefinition
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.AssetKind
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogVersionRange
import gg.grounds.scene.format.EulerRotation
import gg.grounds.scene.format.LocalBounds
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.LookBehavior
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.Prop
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneTrigger
import gg.grounds.scene.format.Transform
import gg.grounds.scene.format.TriggerBinding
import gg.grounds.scene.format.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Exercises the Bukkit adapter against a real editor service, not merely parser routes. */
class SceneCommandAdapterTest {
    @TempDir lateinit var root: Path
    private var server: ServerMock? = null

    @AfterEach
    fun tearDown() {
        server = null
        MockBukkit.unmock()
    }

    @Test
    fun `catalog status is available to console but every other route requires a player`() {
        val fixture = fixture()
        val console = mock(CommandSender::class.java)
        `when`(console.hasPermission("grounds.scene.catalogs.status")).thenReturn(true)
        `when`(console.hasPermission("grounds.scene.info")).thenReturn(true)

        fixture.command.execute(console, "catalogs", "status")
        fixture.command.execute(console, "info")

        assertTrue(fixture.feedback.infos.single().contains("Asset catalog"))
        assertTrue(fixture.feedback.errors.single().contains("requires a player"))
    }

    @Test
    fun `tool give does not invent a scene and gives the exact tagged item`() {
        val fixture = fixture()
        val tool = EditorTool(fixture.plugin)
        val command =
            SceneCommand(
                fixture.plugin,
                fixture.sessions,
                fixture.catalogs,
                SceneWorldResolver { BuildWorldTarget(fixture.worldId, fixture.root) },
                SyncScheduler,
                fixture.feedback,
                tool,
            )

        command.execute(fixture.player, "tool", "give")

        assertTrue(tool.isTool(fixture.player.inventory.contents.filterNotNull().single()))
        assertNull(fixture.sessions.session(fixture.worldId))
    }

    @Test
    fun `lazy bootstrap distinguishes absent loaded invalid and rejected sources`() {
        val absent = fixture()
        absent.command.execute(absent.player, "info")
        assertTrue(absent.feedback.infos.any { it.contains("No scene.json exists") })
        assertNull(absent.sessions.session(absent.worldId))

        val loaded = fixture()
        val seed = EditorSessionService(loaded.catalogs)
        seed.open(loaded.worldId, loaded.catalogs.newDocument("grounds:loaded"))
        seed.save(loaded.worldId, WorldSceneRepository(loaded.root))
        loaded.command.execute(loaded.player, "info")
        assertTrue(loaded.feedback.infos.any { it.contains("Scene grounds:loaded") })

        val invalid = fixture()
        Files.writeString(invalid.root.resolve("scene.json"), "not json")
        invalid.command.execute(invalid.player, "info")
        assertTrue(invalid.feedback.errors.any { it.contains("invalid") })

        val rejected = fixture()
        Files.createDirectory(rejected.root.resolve("scene.json"))
        rejected.command.execute(rejected.player, "info")
        assertTrue(rejected.feedback.errors.any { it.contains("could not be safely read") })
    }

    @Test
    fun `create and invalid recovery create a real session atomically`() {
        val fresh = fixture()
        fresh.command.execute(fresh.player, "create", "grounds:fresh", "Fresh Scene")
        assertEquals("grounds:fresh", fresh.document().id.value)

        val invalid = fixture()
        Files.writeString(invalid.root.resolve("scene.json"), "invalid json")
        invalid.command.execute(
            invalid.player,
            "recovery",
            "backup-and-create",
            "grounds:recovered",
            "Recovered",
        )

        assertEquals("grounds:recovered", invalid.document().id.value)
        assertTrue(
            Files.readString(invalid.root.resolve("scene.json")).contains("grounds:recovered")
        )
        Files.list(invalid.root).use { files ->
            assertTrue(files.anyMatch { it.fileName.toString().contains("scene.json.invalid") })
        }
    }

    @Test
    fun `discard needs literal confirmation and finite values never enter the session`() {
        val fixture = fixtureWithProp()
        fixture.sessions.save(fixture.worldId, WorldSceneRepository(fixture.root))
        fixture.command.execute(fixture.player, "prop", "marker", "position", "set", "4", "5", "6")
        fixture.command.execute(fixture.player, "recovery", "discard-and-reload", "yes")
        assertTrue(fixture.feedback.errors.any { it.contains("Literal confirmation") })
        assertEquals(
            Vec3(4.0, 5.0, 6.0),
            (fixture.document().elements.single() as Prop).transform.position,
        )

        fixture.command.execute(fixture.player, "prop", "marker", "scale", "set", "NaN")
        fixture.command.execute(
            fixture.player,
            "prop",
            "marker",
            "rotation",
            "set",
            "Infinity",
            "0",
            "0",
        )
        assertEquals(
            Vec3(1.0, 1.0, 1.0),
            (fixture.document().elements.single() as Prop).transform.scale,
        )
        assertTrue(fixture.feedback.errors.count { it.contains("finite") } >= 2)

        fixture.command.execute(fixture.player, "recovery", "discard-and-reload", "confirm")
        assertTrue(fixture.feedback.infos.any { it.contains("Discarded scene") })
        assertFalse(fixture.sessions.hasUnsavedChanges(fixture.worldId))
    }

    @Test
    fun `prop and npc edits mutate the actual selected session state`() {
        val fixture = fixture()
        fixture.command.execute(fixture.player, "create", "grounds:editing", "Editing")
        fixture.command.execute(fixture.player, "prop", "marker", "create", "grounds:editor/marker")
        fixture.command.execute(fixture.player, "prop", "marker", "select")
        fixture.command.execute(fixture.player, "prop", "marker", "position", "set", "1", "2", "3")
        fixture.command.execute(fixture.player, "prop", "marker", "position", "add", "1", "1", "1")
        fixture.command.execute(fixture.player, "prop", "marker", "position", "here")
        fixture.command.execute(fixture.player, "prop", "marker", "rotation", "set", "1", "2", "3")
        fixture.command.execute(fixture.player, "prop", "marker", "rotation", "add", "1", "1", "1")
        fixture.command.execute(fixture.player, "prop", "marker", "scale", "set", "2")
        fixture.command.execute(fixture.player, "prop", "marker", "clone", "marker-copy")
        assertEquals(2, fixture.document().elements.filterIsInstance<Prop>().size)
        fixture.command.execute(fixture.player, "prop", "marker", "remove")
        assertEquals("marker-copy", fixture.document().elements.single().id.value)

        fixture.command.execute(fixture.player, "npc", "guide", "create", "grounds:editor/guide")
        fixture.command.execute(fixture.player, "npc", "guide", "select")
        fixture.command.execute(fixture.player, "npc", "guide", "position", "set", "1", "2", "3")
        fixture.command.execute(fixture.player, "npc", "guide", "position", "add", "1", "1", "1")
        fixture.command.execute(fixture.player, "npc", "guide", "position", "here")
        fixture.command.execute(fixture.player, "npc", "guide", "rotation", "set", "1", "2", "3")
        fixture.command.execute(fixture.player, "npc", "guide", "rotation", "add", "1", "1", "1")
        fixture.command.execute(fixture.player, "npc", "guide", "scale", "set", "3")
        fixture.command.execute(fixture.player, "npc", "guide", "label", "set", "Guide NPC")
        fixture.command.execute(fixture.player, "npc", "guide", "clone", "guide-copy")
        val guide =
            fixture.document().elements.filterIsInstance<Npc>().first { it.id.value == "guide" }
        assertEquals(Vec3(3.0, 3.0, 3.0), guide.transform.scale)
        assertEquals("Guide NPC", PlainTextComponentSerializer.plainText().serialize(guide.label!!))
        fixture.command.execute(fixture.player, "npc", "guide", "remove")
        assertTrue(fixture.document().elements.any { it.id.value == "guide-copy" })
    }

    @Test
    fun `mixed case prop recovery and lease actions execute identically`() {
        val fixture = fixtureWithProp()
        fixture.command.execute(fixture.player, "PROP", "marker", "SELECT")
        fixture.command.execute(fixture.player, "PROP", "marker", "POSITION", "ADD", "1", "2", "3")
        fixture.command.execute(fixture.player, "LEASE", "STATUS", "marker")
        fixture.command.execute(fixture.player, "PROP", "LIST")
        fixture.command.execute(fixture.player, "RECOVERY", "EXPORT")

        assertEquals(
            Vec3(1.0, 7.0, 3.0),
            (fixture.document().elements.single() as Prop).transform.position,
        )
        assertTrue(fixture.feedback.infos.any { it.startsWith("prop page") })
        assertTrue(fixture.feedback.infos.any { it.contains("Lease for marker") })
    }

    @Test
    fun `lease release is owner bound and override takes over while clearing displaced selection`() {
        val fixture = fixtureWithProp()
        val id = LocalId("marker")
        fixture.command.execute(fixture.player, "lease", "release", "marker")
        assertTrue(
            fixture.sessions.leaseStatus(fixture.worldId, id)
                is gg.grounds.scene.editor.session.LeaseStatusResult.Available
        )

        fixture.command.execute(fixture.player, "prop", "marker", "select")
        val foreign = mock(Player::class.java)
        `when`(foreign.uniqueId).thenReturn(UUID.randomUUID())
        `when`(foreign.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
        `when`(foreign.hasPermission("grounds.scene.lease.override")).thenReturn(false)
        fixture.command.execute(foreign, "lease", "release", "marker")
        assertTrue(
            fixture.sessions.leaseStatus(fixture.worldId, id)
                is gg.grounds.scene.editor.session.LeaseStatusResult.Held
        )
        assertEquals(
            id,
            fixture.sessions.selection(fixture.worldId, fixture.player.uniqueId)?.elementId,
        )

        `when`(foreign.hasPermission("grounds.scene.lease.override")).thenReturn(true)
        fixture.command.execute(foreign, "lease", "override", "marker")
        assertNull(fixture.sessions.selection(fixture.worldId, fixture.player.uniqueId))
        assertEquals(id, fixture.sessions.selection(fixture.worldId, foreign.uniqueId)?.elementId)
    }

    @Test
    fun `list pagination save wording and info action keys are user visible`() {
        val fixture = fixtureWithProp()
        (2..21).forEach { n ->
            fixture.command.execute(fixture.player, "prop", "marker", "clone", "marker-$n")
            fixture.command.execute(fixture.player, "prop", "marker", "select")
        }
        fixture.command.execute(fixture.player, "prop", "list", "2")
        assertTrue(fixture.feedback.infos.last().startsWith("prop page 2/2:"))

        fixture.command.execute(fixture.player, "save")
        assertTrue(fixture.feedback.infos.any { it.contains("saved locally", true) })
        assertFalse(fixture.feedback.infos.any { it.contains("published", true) })

        val actionFixture = fixture()
        actionFixture.sessions.open(actionFixture.worldId, actionDocument(actionFixture.catalogs))
        actionFixture.command.execute(actionFixture.player, "info")
        assertTrue(actionFixture.feedback.infos.last().contains("grounds:award"))
        assertTrue(actionFixture.feedback.infos.last().contains("read-only"))
    }

    @Test
    fun `deferred reload never discards edits made after preparation and offline save is silent`() {
        val fixture = fixtureWithProp()
        fixture.sessions.save(fixture.worldId, WorldSceneRepository(fixture.root))
        val deferred = DeferredScheduler()
        val reload = adapter(fixture, deferred)
        reload.execute(fixture.player, "reload")
        fixture.command.execute(fixture.player, "prop", "marker", "position", "set", "9", "8", "7")
        deferred.runAll()
        assertEquals(
            Vec3(9.0, 8.0, 7.0),
            (fixture.document().elements.single() as Prop).transform.position,
        )
        assertTrue(fixture.feedback.errors.any { it.contains("changed while reloading") })

        val online = AtomicBoolean(true)
        val player = mock(Player::class.java)
        `when`(player.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
        `when`(player.isOnline).thenAnswer { online.get() }
        val save = adapter(fixture, deferred)
        save.execute(player, "save")
        online.set(false)
        deferred.runAll()
        assertFalse(fixture.feedback.infos.any { it == "Scene saved locally to scene.json." })
    }

    @Test
    fun `offline clean reload reconciles its snapshot without emitting feedback`() {
        val fixture = fixtureWithProp()
        fixture.sessions.save(fixture.worldId, WorldSceneRepository(fixture.root))
        val online = AtomicBoolean(true)
        val player = player(online)
        val deferred = DeferredScheduler()

        adapter(fixture, deferred).execute(player, "reload")
        online.set(false)
        deferred.runAll()

        assertTrue(
            fixture.sessions.prepareReload(fixture.worldId) is ReloadPreparationResult.Prepared
        )
        assertFalse(fixture.feedback.infos.any { it == "Scene reloaded from disk." })
    }

    @Test
    fun `async failures report to online players and stay silent for offline players`() {
        val fixture = fixtureWithProp()
        val online = AtomicBoolean(true)
        val player = player(online)
        val command = adapter(fixture, FailingScheduler)

        command.execute(player, "save")
        assertTrue(fixture.feedback.errors.any { it.contains("Scene save failed: test failure") })
        val reported = fixture.feedback.errors.size
        online.set(false)
        command.execute(player, "save")
        assertEquals(reported, fixture.feedback.errors.size)
    }

    private fun fixtureWithProp(): Fixture =
        fixture().also {
            it.command.execute(it.player, "create", "grounds:editing", "Editing")
            it.command.execute(it.player, "prop", "marker", "create", "grounds:editor/marker")
            it.command.execute(it.player, "prop", "marker", "select")
        }

    private fun fixture(): Fixture {
        val server = server ?: MockBukkit.mock().also { server = it }
        val plugin = MockBukkit.createMockPlugin()
        val player = server.addPlayer()
        player.isOp = true
        player.addAttachment(plugin).setPermission("grounds.scene.edit", true)
        val worldId = UUID.randomUUID()
        val worldRoot = Files.createDirectories(root.resolve(worldId.toString()))
        val catalogs = catalogBinding()
        val sessions = EditorSessionService(catalogs)
        val feedback = Feedback()
        val command =
            SceneCommand(
                plugin,
                sessions,
                catalogs,
                SceneWorldResolver { BuildWorldTarget(worldId, worldRoot) },
                SyncScheduler,
                feedback,
            )
        return Fixture(plugin, command, sessions, catalogs, player, worldId, worldRoot, feedback)
    }

    private fun adapter(fixture: Fixture, scheduler: SceneCommandScheduler): SceneCommand =
        SceneCommand(
            MockBukkit.createMockPlugin(),
            fixture.sessions,
            fixture.catalogs,
            SceneWorldResolver { BuildWorldTarget(fixture.worldId, fixture.root) },
            scheduler,
            fixture.feedback,
        )

    private fun player(online: AtomicBoolean): Player =
        mock(Player::class.java).also { player ->
            `when`(player.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
            `when`(player.isOnline).thenAnswer { online.get() }
        }

    private fun SceneCommand.execute(sender: CommandSender, vararg args: String) {
        assertTrue(onCommand(sender, mock(Command::class.java), "scene", args as Array<String>))
    }

    private fun Fixture.document(): SceneDocument =
        requireNotNull(sessions.session(worldId)).document

    private fun catalogBinding() =
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
                        ),
                    AssetKey("grounds:editor/guide") to
                        AssetDefinition(
                            AssetKey("grounds:editor/guide"),
                            AssetKind.NPC_BODY,
                            emptySet(),
                            LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                            emptyMap(),
                        ),
                ),
            ),
            ActionCatalog(CatalogId("grounds:actions"), "1", emptyMap()),
        )

    private fun actionDocument(catalogs: SceneCatalogBinding): SceneDocument {
        val npc =
            Npc(
                LocalId("guide"),
                null,
                Transform(Vec3(0.0, 0.0, 0.0), EulerRotation(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0)),
                body = AssetKey("grounds:editor/guide"),
                label = null,
                labelOffset = Vec3(0.0, 2.25, 0.0),
                look = LookBehavior.Fixed,
                initialAnimation = null,
                interactionBounds = LocalBounds(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 2.0, 1.0)),
                proximity = null,
                bindings =
                    listOf(
                        TriggerBinding(
                            SceneTrigger.LEFT_CLICK,
                            emptyList(),
                            0,
                            0,
                            listOf(ApplicationAction(ActionKey("grounds:award"), emptyMap())),
                        )
                    ),
            )
        return catalogs.newDocument("grounds:actions", elements = listOf(npc))
    }

    private data class Fixture(
        val plugin: JavaPlugin,
        val command: SceneCommand,
        val sessions: EditorSessionService,
        val catalogs: SceneCatalogBinding,
        val player: Player,
        val worldId: UUID,
        val root: Path,
        val feedback: Feedback,
    )

    private class Feedback : SceneCommandFeedback {
        val infos = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun info(sender: CommandSender, message: String) {
            infos += message
        }

        override fun error(sender: CommandSender, message: String) {
            errors += message
        }
    }

    private object SyncScheduler : SceneCommandScheduler {
        override fun <T> asyncThenMain(
            task: () -> T,
            failure: (Throwable) -> Unit,
            apply: (T) -> Unit,
        ): CompletableFuture<T> =
            try {
                task().also(apply).let { CompletableFuture.completedFuture(it) }
            } catch (error: Throwable) {
                failure(error)
                CompletableFuture.failedFuture(error)
            }
    }

    private class DeferredScheduler : SceneCommandScheduler {
        private val tasks = mutableListOf<() -> Unit>()

        override fun <T> asyncThenMain(
            task: () -> T,
            failure: (Throwable) -> Unit,
            apply: (T) -> Unit,
        ): CompletableFuture<T> {
            val future = CompletableFuture<T>()
            tasks += {
                try {
                    task().also(apply).also(future::complete)
                } catch (error: Throwable) {
                    failure(error)
                    future.completeExceptionally(error)
                }
            }
            return future
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).invoke()
        }
    }

    private object FailingScheduler : SceneCommandScheduler {
        override fun <T> asyncThenMain(
            task: () -> T,
            failure: (Throwable) -> Unit,
            apply: (T) -> Unit,
        ): CompletableFuture<T> {
            val error = IllegalStateException("test failure")
            failure(error)
            return CompletableFuture.failedFuture(error)
        }
    }
}
