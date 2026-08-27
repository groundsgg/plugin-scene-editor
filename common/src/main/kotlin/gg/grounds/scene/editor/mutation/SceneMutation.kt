package gg.grounds.scene.editor.mutation

import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.format.LocalId
import gg.grounds.scene.format.SceneDocument
import java.util.UUID

sealed interface SceneMutation {
    val name: String
    val actor: UUID
    val target: LocalId?

    fun apply(document: SceneDocument, catalogs: SceneCatalogBinding): SceneMutationResult
}
