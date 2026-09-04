package works.resolve.pathfinder.ai.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.filterCatalogModels

class GithubCopilotTest {

    private val models = listOf(
        model("gpt-4.5"),
        model("gpt-4.1"),
        model("claude-haiku-4.5")
    )

    private fun model(id: String) = Model(
        id = id,
        name = id,
        api = "openai-completions",
        provider = "github-copilot",
        baseUrl = "https://api.individual.githubcopilot.com"
    )

    private fun oauth(
        extras: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
    ): OAuthCredential =
        OAuthCredential(access = "a", refresh = "r", expires = Long.MAX_VALUE, extras = extras)

    private fun ids(result: List<Model>) = result.map { it.id }

    @Test
    fun noCredential_returnsAll() {
        assertEquals(ids(models), ids(filterGitHubCopilotModels(models, null)))
    }

    @Test
    fun apiKeyCredential_returnsAll() {
        val credential: Credential = ApiKeyCredential(key = "tok")
        assertEquals(ids(models), ids(filterGitHubCopilotModels(models, credential)))
    }

    @Test
    fun oauthWithoutExtra_returnsAll() {
        assertEquals(ids(models), ids(filterGitHubCopilotModels(models, oauth())))
    }

    @Test
    fun oauthWithNonArrayExtra_returnsAll() {
        val string = oauth(mapOf("availableModelIds" to JsonPrimitive("gpt-4.1")))
        assertEquals(ids(models), ids(filterGitHubCopilotModels(models, string)))
        val objectExtra = oauth(
            mapOf("availableModelIds" to JsonObject(mapOf("gpt-4.1" to JsonPrimitive(true))))
        )
        assertEquals(ids(models), ids(filterGitHubCopilotModels(models, objectExtra)))
    }

    @Test
    fun oauthWithMixedArray_returnsAll() {
        val mixed = oauth(
            mapOf(
                "availableModelIds" to JsonArray(
                    listOf(JsonPrimitive("gpt-4.1"), JsonPrimitive(3))
                )
            )
        )
        assertEquals(ids(models), ids(filterGitHubCopilotModels(models, mixed)))
    }

    @Test
    fun validStringArray_filtersInCatalogOrder_andDropsUnknownIds() {
        // Available order deliberately differs from catalog order; unknown
        // ids are never matched (pi filters the static list against a Set).
        val filtered = filterGitHubCopilotModels(
            models,
            oauth(
                mapOf(
                    "availableModelIds" to JsonArray(
                        listOf(
                            JsonPrimitive("gpt-4.1"),
                            JsonPrimitive("not-in-catalog"),
                            JsonPrimitive("claude-haiku-4.5")
                        )
                    )
                )
            )
        )
        assertEquals(listOf("gpt-4.1", "claude-haiku-4.5"), ids(filtered))
    }

    @Test
    fun validEmptyStringArray_filtersEverythingOut() {
        val empty = oauth(mapOf("availableModelIds" to JsonArray(emptyList())))
        assertEquals(emptyList<Model>(), filterGitHubCopilotModels(models, empty))
    }

    @Test
    fun filterCatalogModels_dispatchesOnlyForCopilot() {
        val copilot = CatalogProvider(
            id = "github-copilot",
            name = "GitHub Copilot",
            baseUrl = "https://api.individual.githubcopilot.com",
            models = models
        )
        val other = CatalogProvider(
            id = "zai",
            name = "Z.AI",
            baseUrl = "https://api.z.ai",
            models = models
        )
        val credential = oauth(
            mapOf("availableModelIds" to JsonArray(listOf(JsonPrimitive("gpt-4.1"))))
        )
        assertEquals(listOf("gpt-4.1"), ids(filterCatalogModels(copilot, credential)))
        // Non-Copilot providers have no filterModels upstream: full list.
        assertEquals(ids(models), ids(filterCatalogModels(other, credential)))
    }
}
