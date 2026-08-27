package gg.grounds.scene.editor.tool

import gg.grounds.scene.format.Transform

enum class TransformComponent {
    X,
    Y,
    Z,
    YAW,
    PITCH,
    ROLL,
    SCALE;

    fun next(): TransformComponent = entries[(ordinal + 1) % entries.size]

    fun previous(): TransformComponent = entries[(ordinal + entries.size - 1) % entries.size]

    fun value(transform: Transform): Double =
        when (this) {
            X -> transform.position.x
            Y -> transform.position.y
            Z -> transform.position.z
            YAW -> transform.rotation.yaw
            PITCH -> transform.rotation.pitch
            ROLL -> transform.rotation.roll
            SCALE -> transform.scale.x
        }
}

enum class TransformStep {
    FINE,
    NORMAL,
    COARSE,
}
