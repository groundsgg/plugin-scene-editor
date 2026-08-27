package gg.grounds.scene.editor.catalog

import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.CatalogId
import gg.grounds.scene.format.CatalogReference
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.SceneCatalogReferences
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneId
import gg.grounds.scene.format.SceneMetadata
import gg.grounds.scene.format.SceneValidation
import gg.grounds.scene.format.SceneValidationResult

/** Immutable classpath catalog snapshot used by a scene-editor runtime. */
class SceneCatalogBinding(val assets: AssetCatalog, val actions: ActionCatalog) {
    val references: SceneCatalogReferences =
        SceneCatalogReferences(
            CatalogReference(assets.id, assets.version),
            CatalogReference(actions.id, actions.version),
        )

    fun newDocument(
        id: String,
        metadata: SceneMetadata = SceneMetadata(id, null, emptySet()),
        elements: List<gg.grounds.scene.format.SceneElement> = emptyList(),
    ): SceneDocument = SceneDocument(1, SceneId(id), metadata, references, emptyList(), elements)

    fun status(document: SceneDocument): CatalogStatus {
        val intrinsic = SceneValidation.validateIntrinsic(document)
        val catalogOnly =
            SceneValidation.validateCatalogs(document, assets, actions).problems.toMutableList()
        intrinsic.problems.forEach(catalogOnly::remove)
        return CatalogStatus(
            document.catalogs.assets == references.assets,
            document.catalogs.actions == references.actions,
            SceneValidationResult(catalogOnly),
        )
    }

    /**
     * True only when the action pin matches and every present application action is catalog-valid.
     */
    fun actionsVerified(document: SceneDocument): Boolean =
        document.catalogs.actions == references.actions &&
            applicationActions(document).all { (path, action) ->
                actionVerified(document, path, action)
            }

    /** Validates one preserved application action at its exact document path. */
    fun actionVerified(document: SceneDocument, action: ApplicationAction): Boolean {
        val matches = applicationActions(document).filter { (_, candidate) -> candidate === action }
        return matches.isNotEmpty() &&
            matches.all { (path, candidate) -> actionVerified(document, path, candidate) }
    }

    private fun actionVerified(
        document: SceneDocument,
        path: String,
        action: ApplicationAction,
    ): Boolean =
        document.catalogs.actions == references.actions &&
            actions.actions.containsKey(action.key) &&
            status(document).validation.problems.none { problem ->
                problem.path == path || problem.path.startsWith("$path/")
            }

    private fun applicationActions(document: SceneDocument): List<Pair<String, ApplicationAction>> =
        document.elements.filterIsInstance<Npc>().flatMap { npc ->
            npc.bindings.flatMapIndexed { bindingIndex, binding ->
                binding.actions.mapIndexedNotNull { actionIndex, action ->
                    (action as? ApplicationAction)?.let {
                        "elements/${npc.id.value}/bindings/$bindingIndex/actions/$actionIndex" to it
                    }
                }
            }
        }

    companion object {
        /**
         * Production binding: exact resource-pack catalog plus the intentionally empty action
         * catalog.
         */
        fun production(): SceneCatalogBinding =
            SceneCatalogBinding(
                GroundsAssetCatalog.catalog,
                ActionCatalog(CatalogId("grounds:actions"), "1", emptyMap()),
            )
    }
}
