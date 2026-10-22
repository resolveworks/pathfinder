package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Usage

class OpenRouterCostTest {

    private fun target(provider: String, baseUrl: String): ClientIdentityTarget =
        object : ClientIdentityTarget {
            override val provider = provider
            override val baseUrl = baseUrl
        }

    private fun usage(total: Double = 1.0) = Usage(
        input = 1,
        output = 1,
        totalTokens = 2,
        cost = Cost(input = 0.25, output = 0.75, total = total)
    )

    private fun reported(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `recognizes both the built-in provider and custom providers using OpenRouter's endpoint`() {
        assertTrue(isOpenRouterModel(target("openrouter", "https://example.com")))
        assertTrue(isOpenRouterModel(target("openrouter-work", "https://openrouter.ai/api/v1")))
        assertFalse(isOpenRouterModel(target("custom", "https://example.com/v1")))
    }

    @Test
    fun `uses OpenRouter's reported cost for non-BYOK requests`() {
        val value = applyOpenRouterReportedCost(
            usage(),
            reported(
                """
                {"cost":0,"is_byok":false,"cost_details":{
                  "upstream_inference_cost":0,
                  "upstream_inference_prompt_cost":0,
                  "upstream_inference_completions_cost":0
                }}
                """
            )
        )
        assertEquals(0.0, value.cost.total)
        assertTrue(value.cost.totalFromProvider)
    }

    @Test
    fun `adds the upstream inference cost for BYOK requests`() {
        val value = applyOpenRouterReportedCost(
            usage(),
            reported(
                """
                {"cost":0.1,"is_byok":true,"cost_details":{
                  "upstream_inference_cost":0.9,
                  "upstream_inference_prompt_cost":0.6,
                  "upstream_inference_completions_cost":0.3
                }}
                """
            )
        )
        assertEquals(1.0, value.cost.total, 1e-12)
        assertTrue(value.cost.totalFromProvider)
    }

    @Test
    fun `uses upstream cost components when OpenRouter omits their total`() {
        val value = applyOpenRouterReportedCost(
            usage(),
            reported(
                """
                {"cost":0.1,"is_byok":true,"cost_details":{
                  "upstream_inference_cost":null,
                  "upstream_inference_prompt_cost":0.6,
                  "upstream_inference_completions_cost":0.3
                }}
                """
            )
        )
        assertEquals(1.0, value.cost.total, 1e-12)
        assertTrue(value.cost.totalFromProvider)
    }

    @Test
    fun `keeps the catalog total when OpenRouter's accounting is incomplete`() {
        val missingCost = applyOpenRouterReportedCost(
            usage(),
            reported("""{"cost":null,"is_byok":false,"cost_details":null}""")
        )
        assertEquals(1.0, missingCost.cost.total)
        assertFalse(missingCost.cost.totalFromProvider)

        val missingDetails = applyOpenRouterReportedCost(
            usage(),
            reported("""{"cost":0.1,"is_byok":true,"cost_details":null}""")
        )
        assertEquals(1.0, missingDetails.cost.total)
        assertFalse(missingDetails.cost.totalFromProvider)
    }
}
