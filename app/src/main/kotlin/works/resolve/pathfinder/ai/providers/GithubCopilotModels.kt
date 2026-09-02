package works.resolve.pathfinder.ai.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.core.Model

/** pi's GitHub Copilot provider id. */
const val GITHUB_COPILOT_PROVIDER_ID = "github-copilot"

/**
 * Catalog-data analogue of pi's per-provider `filterModels` hook
 * (`provider.filterModels?.(models, credential) ?? models`), which GitHub
 * Copilot alone defines. The dispatch lives here instead of a `filterModels`
 * field on [CatalogProvider] because the catalog is generated data, while the
 * filter is runtime behavior tied to the credential shape.
 */
fun filterCatalogModels(provider: CatalogProvider, credential: Credential?): List<Model> =
    if (provider.id == GITHUB_COPILOT_PROVIDER_ID) {
        filterGitHubCopilotModels(provider.models, credential)
    } else {
        provider.models
    }

/**
 * Mirrors pi's GitHub Copilot `filterModels`: an OAuth credential filters the
 * static models against its `availableModelIds` extra. A missing or
 * non-string-array extra returns ALL models; a valid array — including an
 * empty one — keeps only the listed ids, so an empty result is legitimate.
 */
fun filterGitHubCopilotModels(models: List<Model>, credential: Credential?): List<Model> {
    if (credential !is OAuthCredential) return models
    val extra = credential.extras["availableModelIds"] ?: return models
    if (extra !is JsonArray || !extra.all { it is JsonPrimitive && it.isString }) return models
    val available = extra.map { (it as JsonPrimitive).content }.toSet()
    return models.filter { it.id in available }
}
