package gg.grounds.scene.editor.repository

import java.security.MessageDigest

/** SHA-256 of exact source bytes; absence is intentionally distinct from an empty file. */
sealed interface SceneFingerprint {
    data object Absent : SceneFingerprint

    class Present private constructor(private val digest: ByteArray) : SceneFingerprint {
        fun bytes(): ByteArray = digest.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Present && digest.contentEquals(other.digest)

        override fun hashCode(): Int = digest.contentHashCode()

        companion object {
            internal fun fromDigest(value: ByteArray) = Present(value.copyOf())
        }
    }

    companion object {
        fun of(bytes: ByteArray): SceneFingerprint =
            Present.fromDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
