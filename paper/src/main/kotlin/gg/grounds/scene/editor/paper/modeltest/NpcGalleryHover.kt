package gg.grounds.scene.editor.paper.modeltest

import gg.grounds.scene.format.*
import gg.grounds.scene.interaction.SceneBoundsRaycaster
import gg.grounds.scene.interaction.SceneHoverHit
import gg.grounds.scene.interaction.SceneHoverSelector
import java.util.UUID

/** Gallery documents have no groups; geometry and selection state are shared with the lobby. */
internal class NpcGalleryHover {
    private val selector = SceneHoverSelector()

    fun update(viewer: UUID, document: SceneDocument, eye: Vec3, direction: Vec3): LocalId? {
        val hits =
            document.elements
                .filterIsInstance<Npc>()
                .filter { it.visible }
                .mapNotNull { npc ->
                    SceneBoundsRaycaster.distance(
                            npc.interactionBounds,
                            npc.transform,
                            null,
                            eye,
                            direction,
                            5.0,
                        )
                        ?.let { SceneHoverHit(npc.id, it) }
                }
        selector.update(viewer, hits)
        return selector.current(viewer)
    }

    fun current(viewer: UUID): LocalId? = selector.current(viewer)

    fun removeViewer(viewer: UUID) {
        selector.removeViewer(viewer)
    }
}
