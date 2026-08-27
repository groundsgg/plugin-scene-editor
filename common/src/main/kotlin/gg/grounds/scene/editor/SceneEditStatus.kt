package gg.grounds.scene.editor

import java.util.UUID

/** Reports whether a build world's active scene session contains unsaved edits. */
fun interface SceneEditStatus {
    fun hasUnsavedChanges(worldId: UUID): Boolean
}
