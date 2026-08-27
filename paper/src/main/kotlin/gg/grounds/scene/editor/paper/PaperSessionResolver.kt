package gg.grounds.scene.editor.paper

import de.eintosti.buildsystem.api.BuildSystemProvider
import de.eintosti.buildsystem.api.world.BuildWorld
import gg.grounds.scene.editor.paper.command.SceneWorldResolver
import java.nio.file.Path
import java.util.UUID
import org.bukkit.World
import org.bukkit.entity.Player

/** Resolves only the exact loaded BuildSystem world currently occupied by a player. */
class PaperSessionResolver(
    private val buildWorldFor: (World) -> BuildWorld? = {
        BuildSystemProvider.get().worldService.worldStorage.getBuildWorld(it)
    }
) : SceneWorldResolver {
    override fun resolve(player: Player): BuildWorldTarget? =
        try {
            val world = player.world
            val buildWorld = buildWorldFor(world) ?: return null
            if (buildWorld.world.orElse(null) !== world) return null
            BuildWorldTarget(world.uid, world.worldFolder.toPath())
        } catch (_: IllegalStateException) {
            null
        }
}

data class BuildWorldTarget(val worldId: UUID, val worldFolder: Path)
