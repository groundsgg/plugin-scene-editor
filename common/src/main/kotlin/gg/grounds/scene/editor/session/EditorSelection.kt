package gg.grounds.scene.editor.session

import gg.grounds.scene.format.LocalId

/** Per-player ephemeral selection; never part of the scene document. */
data class EditorSelection(val elementId: LocalId)
