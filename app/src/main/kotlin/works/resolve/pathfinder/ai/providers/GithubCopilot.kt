/**
 * Partial twin of pi's `providers/github-copilot.ts`: the provider def
 * itself is catalog data baked into the generated asset (upstream's
 * `github-copilot.models.ts` generated binding has no Kotlin twin), so this
 * file holds only the ported runtime behavior — the provider's
 * `filterModels` hook.
 */
package works.resolve.pathfinder.ai.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.core.Model

/** pi's GitHub Copilot provider id. */
const val GITHUB_COPILOT_PROVIDER_ID = "github-copilot"

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
