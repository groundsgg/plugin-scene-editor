package gg.grounds.scene.editor.validation

import gg.grounds.scene.editor.catalog.CatalogStatus
import gg.grounds.scene.editor.catalog.SceneCatalogBinding
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneValidation
import gg.grounds.scene.format.SceneValidationResult

data class SceneValidationState(val intrinsic: SceneValidationResult, val catalogs: CatalogStatus) {
    companion object {
        fun of(document: SceneDocument, binding: SceneCatalogBinding): SceneValidationState =
            SceneValidationState(
                SceneValidation.validateIntrinsic(document),
                binding.status(document),
            )
    }
}
