package gg.grounds.scene.editor.repository

import gg.grounds.scene.editor.session.EditorSessionService.SaveReservation

interface SceneRepository {
    fun load(): SceneLoadResult

    /**
     * Internal capability used solely by [gg.grounds.scene.editor.session.EditorSessionService].
     */
    fun save(reservation: SaveReservation): SceneSaveResult
}
