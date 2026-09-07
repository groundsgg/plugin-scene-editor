package gg.grounds.scene.editor.catalog

import gg.grounds.lobby.scene.LobbySceneCatalogs
import gg.grounds.resourcepacks.catalog.GroundsAssetCatalog
import gg.grounds.scene.format.ActionCatalog
import gg.grounds.scene.format.ApplicationAction
import gg.grounds.scene.format.AssetCatalog
import gg.grounds.scene.format.CatalogReference
import gg.grounds.scene.format.Npc
import gg.grounds.scene.format.SceneCatalogReferences
import gg.grounds.scene.format.SceneDocument
import gg.grounds.scene.format.SceneId
import gg.grounds.scene.format.SceneMetadata
import gg.grounds.scene.format.SceneValidation
import gg.grounds.scene.format.SceneValidationResult

/** Immutable classpath catalog snapshot used by a scene-editor runtime. */
class SceneCatalogBinding
private constructor(
    val assets: AssetCatalog,
    val actions: ActionCatalog,
    private val actionResolver: (CatalogReference) -> ActionCatalog?,
) {
    constructor(
        assets: AssetCatalog,
        actions: ActionCatalog,
    ) : this(
        assets,
        actions,
        { reference -> actions.takeIf { it.id == reference.id && it.version == reference.version } },
    )

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
        val actionCatalog = actionCatalogFor(document)
        val catalogOnly =
            SceneValidation.validateCatalogs(document, assets, actionCatalog ?: actions)
                .problems
                .toMutableList()
        intrinsic.problems.forEach(catalogOnly::remove)
        return CatalogStatus(
            document.catalogs.assets == references.assets,
            actionCatalog != null,
            SceneValidationResult(catalogOnly),
        )
    }

    /**
     * True only when the action pin matches and every present application action is catalog-valid.
     */
    fun actionsVerified(document: SceneDocument): Boolean =
        actionCatalogFor(document) != null &&
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
        actionCatalogFor(document)?.actions?.containsKey(action.key) == true &&
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

    private fun actionCatalogFor(document: SceneDocument): ActionCatalog? =
        actionResolver(document.catalogs.actions)

    companion object {
        /**
         * Production binding: exact resource-pack catalog plus the lobby action catalog resolver.
         */
        fun production(): SceneCatalogBinding =
            SceneCatalogBinding(
                GroundsAssetCatalog.catalog,
                LobbySceneCatalogs.CURRENT,
                LobbySceneCatalogs::resolve,
            )
    }
}
