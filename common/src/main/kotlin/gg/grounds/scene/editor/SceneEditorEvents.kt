package gg.grounds.scene.editor

import gg.grounds.scene.format.LocalId
import java.util.UUID

/** An accepted document mutation, emitted exactly once after its snapshot is installed. */
data class SceneEditorEvent(
    val worldId: UUID,
    val actor: UUID,
    val name: String,
    val target: LocalId?,
)
