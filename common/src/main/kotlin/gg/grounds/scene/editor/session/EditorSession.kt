package gg.grounds.scene.editor.session

import gg.grounds.scene.editor.history.SceneHistory
import gg.grounds.scene.format.SceneDocument
import java.util.UUID

/** A world-scoped session. Mutations are performed only while its service monitor is held. */
class EditorSession internal constructor(val worldId: UUID, initial: SessionState) {
    @Volatile internal var state = initial
    val document: SceneDocument
        get() = state.document

    val history: SceneHistory
        get() = state.history
}
