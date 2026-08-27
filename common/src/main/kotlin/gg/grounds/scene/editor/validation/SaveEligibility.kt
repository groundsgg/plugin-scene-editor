package gg.grounds.scene.editor.validation

enum class SaveIneligibility {
    NO_DOCUMENT,
    RECOVERY_REQUIRED,
    INTRINSIC_INVALID,
    CATALOG_UNVERIFIED,
    FINGERPRINT_CONFLICT,
    SAVE_IN_PROGRESS,
}

data class SaveEligibility(val reasons: Set<SaveIneligibility>) {
    val isEligible: Boolean
        get() = reasons.isEmpty()

    companion object {
        fun from(validation: SceneValidationState): SaveEligibility =
            SaveEligibility(
                buildSet {
                    if (!validation.intrinsic.isValid) add(SaveIneligibility.INTRINSIC_INVALID)
                    if (!validation.catalogs.isVerified) add(SaveIneligibility.CATALOG_UNVERIFIED)
                }
            )
    }
}
