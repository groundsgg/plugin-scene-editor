package gg.grounds.scene.editor.mutation

import gg.grounds.scene.format.SceneDocument

enum class SceneMutationRejection {
    DUPLICATE_ELEMENT_ID,
    ELEMENT_NOT_FOUND,
    WRONG_ELEMENT_KIND,
    UNKNOWN_ASSET,
    WRONG_ASSET_KIND,
    MISSING_NPC_BOUNDS,
    INVALID_SCALE,
    INTRINSIC_INVALID,
    READ_ONLY_APPLICATION_ACTION,
}

sealed interface SceneMutationResult {
    val document: SceneDocument

    data class Success(override val document: SceneDocument) : SceneMutationResult

    data class Rejected(override val document: SceneDocument, val reason: SceneMutationRejection) :
        SceneMutationResult
}
