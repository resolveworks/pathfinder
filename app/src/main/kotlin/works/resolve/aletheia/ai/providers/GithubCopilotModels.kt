package works.resolve.aletheia.ai.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.aletheia.ai.auth.Credential
import works.resolve.aletheia.ai.auth.OAuthCredential
import works.resolve.aletheia.ai.core.Model

/** pi's GitHub Copilot provider id (`packages/ai/src/providers/github-copilot.ts`). */
const val GITHUB_COPILOT_PROVIDER_ID = "github-copilot"

/**
 * Applies the provider's credential-based model filter to its static catalog
 * models — the catalog-data analogue of pi's per-provider `filterModels`
 * hook (`packages/ai/src/models.ts`, applied by `Models.getAvailable` as
 * `provider.filterModels?.(models, credential) ?? models`).
 *
 * GitHub Copilot is currently the only pi provider with a `filterModels`
 * (verified against pi's `packages/ai/src/providers/`); every other provider
 * keeps its full static list. Aletheia keeps this dispatch in one function
 * instead of adding a `filterModels` field to [CatalogProvider], because the
 * catalog is generated data and the filter is runtime behavior tied to the
 * credential shape, not the asset.
 */
fun filterCatalogModels(provider: CatalogProvider, credential: Credential?): List<Model> =
    if (provider.id == GITHUB_COPILOT_PROVIDER_ID) {
        filterGitHubCopilotModels(provider.models, credential)
    } else {
        provider.models
    }

/**
 * Port of pi's GitHub Copilot `filterModels`
 * (`packages/ai/src/providers/github-copilot.ts`), reduced to the generated
 * static catalog (no dynamic model discovery):
 *
 * - An API-key credential or no credential at all returns all static models.
 * - An OAuth credential filters by its `availableModelIds` extra: when the
 *   extra is missing, not a JSON array, or not entirely strings, ALL static
 *   models are returned (pi: `!Array.isArray(...) ||
 *   !availableModelIds.every((id) => typeof id === "string")`).
 * - A valid string array — including an EMPTY one — keeps only the static
 *   models whose ids it lists, in catalog order (pi filters the static list
 *   against a `Set`; unknown ids are simply never matched).
 */
fun filterGitHubCopilotModels(models: List<Model>, credential: Credential?): List<Model> {
    if (credential !is OAuthCredential) return models
    val extra = credential.extras["availableModelIds"] ?: return models
    if (extra !is JsonArray || !extra.all { it is JsonPrimitive && it.isString }) return models
    val available = extra.map { (it as JsonPrimitive).content }.toSet()
    return models.filter { it.id in available }
}
