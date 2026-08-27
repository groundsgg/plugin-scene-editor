package gg.grounds.scene.editor.catalog

import gg.grounds.scene.format.SceneValidationResult

/** The active-catalog compatibility of a document, kept separate from intrinsic validity. */
data class CatalogStatus(
    val assetReferenceMatches: Boolean,
    val actionReferenceMatches: Boolean,
    val validation: SceneValidationResult,
) {
    val isVerified: Boolean
        get() = assetReferenceMatches && actionReferenceMatches && validation.isValid
}
