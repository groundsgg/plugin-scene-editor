package gg.grounds.scene.editor.paper

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.editor.mutation.PlayerPlacement
import gg.grounds.scene.editor.mutation.SceneMutations
import gg.grounds.scene.editor.paper.command.SceneCommandScheduler
import gg.grounds.scene.editor.paper.command.SceneWorldResolver
import gg.grounds.scene.editor.repository.SceneSaveResult
import gg.grounds.scene.editor.repository.WorldSceneRepository
import gg.grounds.scene.editor.session.EditorSessionService
import gg.grounds.scene.format.AssetKey
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PaperAutomaticSceneLoaderTest {
    @TempDir lateinit var temp: Path

    private val catalogs = SceneCatalogBinding.production()

    @Test
    fun `world entry opens an existing scene once after asynchronous load is applied`() {
        val worldId = UUID(1, 1)
        val root = temp.resolve("world")
        val expected = catalogs.newDocument("grounds:auto-open")
        save(root, worldId, expected)
        val fixture = fixture(worldId, root)

        fixture.loader.openFor(fixture.player)
        fixture.loader.openFor(fixture.player)

        assertEquals(1, fixture.scheduler.pendingAsync)
        fixture.scheduler.runNextAsync()
        assertNull(fixture.sessions.session(worldId))
        fixture.scheduler.runNextMain()
        assertEquals(expected, fixture.sessions.session(worldId)?.document)
    }

    @Test
    fun `existing dirty session is neither read nor replaced`() {
        val worldId = UUID(2, 2)
        val root = temp.resolve("dirty-world")
        val original = catalogs.newDocument("grounds:dirty")
        val sessions = EditorSessionService(catalogs)
        sessions.open(worldId, original)
        assertTrue(
            sessions
                .mutate(
                    worldId,
                    SceneMutations.createProp(
                        UUID(9, 9),
                        LocalId("marker"),
                        AssetKey("grounds:editor/marker"),
                        PlayerPlacement(Vec3(1.0, 2.0, 3.0), 0.0),
                    ),
                )
                .accepted
        )
        save(root, worldId, catalogs.newDocument("grounds:on-disk"))
        val fixture = fixture(worldId, root, sessions)

        fixture.loader.openFor(fixture.player)

        assertEquals(0, fixture.scheduler.pendingAsync)
        assertEquals("grounds:dirty", sessions.session(worldId)?.document?.id?.value)
        assertTrue(sessions.hasUnsavedChanges(worldId))
    }

    @Test
    fun `absent and invalid sources remain unopened and unchanged`() {
        val absentId = UUID(3, 3)
        val absentRoot = temp.resolve("absent-world")
        Files.createDirectories(absentRoot)
        val absent = fixture(absentId, absentRoot)
        absent.loader.openFor(absent.player)
        absent.scheduler.runAll()
        assertNull(absent.sessions.session(absentId))
        assertTrue(Files.notExists(absentRoot.resolve("scene.json")))
        val laterDocument = catalogs.newDocument("grounds:added-later")
        save(absentRoot, absentId, laterDocument)
        absent.loader.openFor(absent.player)
        absent.scheduler.runAll()
        assertEquals(laterDocument, absent.sessions.session(absentId)?.document)

        val invalidId = UUID(4, 4)
        val invalidRoot = temp.resolve("invalid-world")
        Files.createDirectories(invalidRoot)
        invalidRoot.resolve("scene.json").writeText("not-json")
        val before = invalidRoot.resolve("scene.json").readBytes()
        val invalid = fixture(invalidId, invalidRoot)
        invalid.loader.openFor(invalid.player)
        invalid.scheduler.runAll()
        assertNull(invalid.sessions.session(invalidId))
        assertTrue(invalidRoot.resolve("scene.json").readBytes().contentEquals(before))
    }

    @Test
    fun `completion from an unloaded world cannot win over re-entry`() {
        val worldId = UUID(5, 5)
        val root = temp.resolve("reentered-world")
        val expected = catalogs.newDocument("grounds:reentered")
        save(root, worldId, expected)
        val fixture = fixture(worldId, root)
        fixture.loader.openFor(fixture.player)
        fixture.scheduler.runNextAsync()

        fixture.loader.invalidate(fixture.world)
        val replacementWorld = world(worldId, root)
        `when`(fixture.server.getWorld(worldId)).thenReturn(replacementWorld)
        val replacementPlayer = player(worldId, replacementWorld)
        fixture.loader.openFor(replacementPlayer)

        fixture.scheduler.runNextMain()
        assertNull(fixture.sessions.session(worldId))
        fixture.scheduler.runAll()
        assertEquals(expected, fixture.sessions.session(worldId)?.document)
    }

    @Test
    fun `close discards an already loaded completion`() {
        val worldId = UUID(6, 6)
        val root = temp.resolve("closed-world")
        save(root, worldId, catalogs.newDocument("grounds:closed"))
        val fixture = fixture(worldId, root)
        fixture.loader.openFor(fixture.player)
        fixture.scheduler.runNextAsync()

        fixture.loader.close()
        fixture.scheduler.runNextMain()

        assertNull(fixture.sessions.session(worldId))
    }

    private fun fixture(
        worldId: UUID,
        root: Path,
        sessions: EditorSessionService = EditorSessionService(catalogs),
    ): Fixture {
        val plugin = mock(JavaPlugin::class.java)
        val server = mock(Server::class.java)
        val world = world(worldId, root)
        val player = player(worldId, world)
        val scheduler = ControlledScheduler()
        `when`(plugin.server).thenReturn(server)
        `when`(plugin.logger).thenReturn(Logger.getAnonymousLogger())
        `when`(server.getWorld(worldId)).thenReturn(world)
        val loader =
            PaperAutomaticSceneLoader(
                plugin,
                sessions,
                SceneWorldResolver { candidate ->
                    if (candidate.world.uid == worldId) worldTarget(worldId, root) else null
                },
                scheduler,
            )
        return Fixture(plugin, server, world, player, sessions, scheduler, loader)
    }

    private fun world(worldId: UUID, root: Path): World =
        mock(World::class.java).also {
            `when`(it.uid).thenReturn(worldId)
            `when`(it.worldFolder).thenReturn(root.toFile())
        }

    private fun player(playerIdSeed: UUID, world: World): Player =
        mock(Player::class.java).also {
            `when`(it.uniqueId).thenReturn(UUID(playerIdSeed.mostSignificantBits, 42))
            `when`(it.world).thenReturn(world)
        }

    private fun worldTarget(worldId: UUID, root: Path) = BuildWorldTarget(worldId, root)

    private fun save(root: Path, worldId: UUID, document: gg.grounds.scene.format.SceneDocument) {
        Files.createDirectories(root)
        val writer = EditorSessionService(catalogs)
        writer.open(worldId, document)
        assertTrue(writer.save(worldId, WorldSceneRepository(root)) is SceneSaveResult.Saved)
    }

    private data class Fixture(
        val plugin: JavaPlugin,
        val server: Server,
        val world: World,
        val player: Player,
        val sessions: EditorSessionService,
        val scheduler: ControlledScheduler,
        val loader: PaperAutomaticSceneLoader,
    )

    private class ControlledScheduler : SceneCommandScheduler {
        private val async = ArrayDeque<() -> Unit>()
        private val main = ArrayDeque<() -> Unit>()
        val pendingAsync: Int
            get() = async.size

        override fun <T> asyncThenMain(
            task: () -> T,
            failure: (Throwable) -> Unit,
            apply: (T) -> Unit,
        ): CompletableFuture<T> {
            val future = CompletableFuture<T>()
            async += {
                try {
                    val value = task()
                    future.complete(value)
                    main += { apply(value) }
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                    main += { failure(error) }
                }
            }
            return future
        }

        fun runNextAsync() = async.removeFirst().invoke()

        fun runNextMain() = main.removeFirst().invoke()

        fun runAll() {
            while (async.isNotEmpty() || main.isNotEmpty()) {
                while (async.isNotEmpty()) runNextAsync()
                while (main.isNotEmpty()) runNextMain()
            }
        }
    }
}
