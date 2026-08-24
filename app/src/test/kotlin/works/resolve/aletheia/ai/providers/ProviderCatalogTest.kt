package works.resolve.aletheia.ai.providers

import works.resolve.aletheia.ai.core.ChatTemplateKwargValue
import works.resolve.aletheia.ai.core.InputModality
import works.resolve.aletheia.ai.core.MaxTokensField
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.ModelThinkingLevel
import works.resolve.aletheia.ai.core.ThinkingFormat
import works.resolve.aletheia.ai.core.ThinkingLevelMap
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Parsing tests for the generated model catalog: lenient unknown-field
 * handling, exact compat mapping (including chatTemplateArgs refs and
 * thinkingLevelMap absent-vs-null), fail-fast unknown enum values, and the
 * real bundled asset.
 */
class ProviderCatalogTest {

    // ---- fixture parsing ----

    @Test
    fun `parses provider identity, auth prompts, and bearer header override`() {
        val catalog = ProviderCatalog.parse(
            """
            {
              "generatedAt": "now",
              "providers": [
                {
                  "id": "cf",
                  "name": "Cloudflare AI Gateway",
                  "baseUrl": "https://gateway.test/v1",
                  "bearerHeaderName": "cf-aig-authorization",
                  "auth": {
                    "label": "Cloudflare API key",
                    "prompts": [
                      {"envKey": "CLOUDFLARE_API_KEY", "message": "Enter Cloudflare API key", "secret": true},
                      {"envKey": "CLOUDFLARE_ACCOUNT_ID", "message": "Enter account ID", "secret": false}
                    ]
                  },
                  "models": []
                }
              ]
            }
            """,
        )
        val provider = catalog.getProvider("cf")!!
        assertEquals("Cloudflare AI Gateway", provider.name)
        assertEquals("cf-aig-authorization", provider.bearerHeaderName)
        assertEquals("Cloudflare API key", provider.auth.label)
        assertEquals(
            listOf(
                AuthPrompt("CLOUDFLARE_API_KEY", "Enter Cloudflare API key", secret = true),
                AuthPrompt("CLOUDFLARE_ACCOUNT_ID", "Enter account ID", secret = false),
            ),
            provider.auth.prompts,
        )
        assertNull(catalog.getProvider("missing"))
    }

    @Test
    fun `parses every compat flag, chatTemplateArgs ref and scalar, and model headers`() {
        val catalog = ProviderCatalog.parse(
            """
            {
              "providers": [
                {
                  "id": "p1", "name": "P1", "baseUrl": "https://p1.test/v1",
                  "models": [
                    {
                      "id": "m1", "name": "M1",
                      "baseUrl": "https://override.test/v1",
                      "reasoning": true,
                      "thinkingLevelMap": {"off": "none", "high": "high"},
                      "input": ["text", "image"],
                      "cost": {"input": 1.5, "output": 3.0, "cacheRead": 0.2, "cacheWrite": 0.4},
                      "contextWindow": 123456, "maxTokens": 4096,
                      "headers": {"X-Custom": "v1", "Accept": "application/json"},
                      "compat": {
                        "supportsStore": false,
                        "supportsDeveloperRole": false,
                        "supportsReasoningEffort": false,
                        "supportsUsageInStreaming": false,
                        "supportsFinishReason": false,
                        "maxTokensField": "max_tokens",
                        "requiresToolResultName": true,
                        "requiresThinkingAsText": true,
                        "thinkingFormat": "qwen",
                        "zaiToolStream": true,
                        "chatTemplateArgs": {
                          "enable_thinking": {"${'$'}var": "thinking.enabled", "omitWhenOff": true},
                          "temperature": 0.7
                        },
                        "supportsStrictMode": true,
                        "cacheControlFormat": "anthropic"
                      }
                    }
                  ]
                }
              ]
            }
            """,
        )
        val model = catalog.getModel("p1", "m1")!!
        assertEquals("openai-completions", model.api)
        assertEquals("p1", model.provider, "model provider falls back to the owner provider id")
        assertEquals("https://override.test/v1", model.baseUrl)
        assertTrue(model.reasoning)
        assertEquals(listOf(InputModality.TEXT, InputModality.IMAGE), model.input)
        assertEquals(1.5, model.cost.input)
        assertEquals(3.0, model.cost.output)
        assertEquals(0.2, model.cost.cacheRead)
        assertEquals(0.4, model.cost.cacheWrite)
        assertEquals(123456, model.contextWindow)
        assertEquals(4096, model.maxTokens)
        assertEquals(mapOf("X-Custom" to "v1", "Accept" to "application/json"), model.headers)

        val compat = model.compat
        assertFalse(compat.supportsStore)
        assertFalse(compat.supportsDeveloperRole)
        assertFalse(compat.supportsReasoningEffort)
        assertFalse(compat.supportsUsageInStreaming)
        assertFalse(compat.supportsFinishReason)
        assertEquals(MaxTokensField.MAX_TOKENS, compat.maxTokensField)
        assertTrue(compat.requiresToolResultName)
        assertTrue(compat.requiresThinkingAsText)
        assertEquals(ThinkingFormat.QWEN, compat.thinkingFormat)
        assertTrue(compat.zaiToolStream)
        assertEquals(
            ChatTemplateKwargValue.Ref(varName = "thinking.enabled", omitWhenOff = true),
            compat.chatTemplateArgs["enable_thinking"],
        )
        assertEquals(
            ChatTemplateKwargValue.Scalar(JsonPrimitive(0.7)),
            compat.chatTemplateArgs["temperature"],
        )
    }

    @Test
    fun `chatTemplateArgs ref defaults omitWhenOff to false`() {
        val model = ProviderCatalog.parse(
            """
            {"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
              {"id":"m","name":"M","compat":{"chatTemplateArgs":{"e":{"${'$'}var":"thinking.enabled"}}}}
            ]}]}
            """,
        ).getModel("p", "m")!!
        val ref = assertIs<ChatTemplateKwargValue.Ref>(model.compat.chatTemplateArgs["e"])
        assertEquals("thinking.enabled", ref.varName)
        assertFalse(ref.omitWhenOff)
    }

    @Test
    fun `distinguishes absent thinking level keys from explicit nulls`() {
        val model = ProviderCatalog.parse(
            """
            {"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
              {"id":"m","name":"M","reasoning":true,
               "thinkingLevelMap":{"off":"none","low":null,"high":"high"}}
            ]}]}
            """,
        ).getModel("p", "m")!!
        assertTrue(model.thinkingLevelMap!!.isSpecified(ModelThinkingLevel.LOW))
        assertNull(model.thinkingLevelMap.forLevel(ModelThinkingLevel.LOW))
        assertFalse(model.thinkingLevelMap.isSpecified(ModelThinkingLevel.MEDIUM))
        assertEquals("high", model.thinkingLevelMap.forLevel(ModelThinkingLevel.HIGH))
    }

    @Test
    fun `absent fields fall back to the core Kotlin defaults`() {
        val model = ProviderCatalog.parse(
            """{"providers":[{"id":"p","name":"P","baseUrl":"https://p.test","models":[{"id":"m","name":"M"}]}]}""",
        ).getModel("p", "m")!!
        assertEquals(listOf(InputModality.TEXT), model.input)
        assertEquals(ThinkingFormat.OPENAI, model.compat.thinkingFormat)
        assertEquals(MaxTokensField.MAX_COMPLETION_TOKENS, model.compat.maxTokensField)
        assertTrue(model.compat.supportsStore)
        assertEquals("https://p.test", model.baseUrl, "model baseUrl falls back to the provider's")
    }

    @Test
    fun `parses every thinking format`() {
        val formats = listOf(
            "openai" to ThinkingFormat.OPENAI,
            "zai" to ThinkingFormat.ZAI,
            "qwen" to ThinkingFormat.QWEN,
            "deepseek" to ThinkingFormat.DEEPSEEK,
            "openrouter" to ThinkingFormat.OPENROUTER,
            "together" to ThinkingFormat.TOGETHER,
            "ant-ling" to ThinkingFormat.ANT_LING,
            "baseten" to ThinkingFormat.BASETEN,
        )
        for ((raw, expected) in formats) {
            val model = ProviderCatalog.parse(
                """
                {"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
                  {"id":"m","name":"M","compat":{"thinkingFormat":"$raw"}}
                ]}]}
                """.trimIndent(),
            ).getModel("p", "m")!!
            assertEquals(expected, model.compat.thinkingFormat, "thinkingFormat '$raw'")
        }
    }

    // ---- fail-fast on unknown enum values ----

    @Test
    fun `unknown thinkingFormat throws with context`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ProviderCatalog.parse(
                """{"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
                   {"id":"m","name":"M","compat":{"thinkingFormat":"nope"}}]}]}""",
            )
        }
        assertContains(error.message!!, "nope")
        assertContains(error.message!!, "p/m")
    }

    @Test
    fun `unknown input modality throws`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderCatalog.parse(
                """{"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
                   {"id":"m","name":"M","input":["audio"]}]}]}""",
            )
        }
    }

    @Test
    fun `unknown maxTokensField throws`() {
        assertFailsWith<IllegalArgumentException> {
            ProviderCatalog.parse(
                """{"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
                   {"id":"m","name":"M","compat":{"maxTokensField":"tokens_please"}}]}]}""",
            )
        }
    }

    // ---- glm-4.7 must parse to exactly the old hand-ported values ----

    @Test
    fun `real asset zai glm-4_7 matches the retired hand-written ZaiModels port`() {
        val model = realAsset().getModel("zai", "glm-4.7")
            ?: error("real asset is missing zai/glm-4.7")
        val expected = Model(
            id = "glm-4.7",
            name = "GLM-4.7",
            api = "openai-completions",
            provider = "zai",
            baseUrl = "https://api.z.ai/api/coding/paas/v4",
            reasoning = true,
            thinkingLevelMap = null,
            input = listOf(InputModality.TEXT),
            cost = works.resolve.aletheia.ai.core.ModelCost(
                input = 0.6,
                output = 2.2,
                cacheRead = 0.11,
                cacheWrite = 0.0,
            ),
            contextWindow = 204_800,
            maxTokens = 131_072,
            compat = works.resolve.aletheia.ai.core.OpenAiCompletionsCompat(
                supportsStore = false,
                supportsDeveloperRole = false,
                supportsReasoningEffort = false,
                supportsUsageInStreaming = true,
                supportsFinishReason = true,
                maxTokensField = MaxTokensField.MAX_TOKENS,
                thinkingFormat = ThinkingFormat.ZAI,
                zaiToolStream = true,
            ),
        )
        assertEquals(expected, model)
    }

    // ---- the real bundled asset ----

    private var realCatalog: ProviderCatalog? = null

    private fun realAsset(): ProviderCatalog {
        // Gradle unit tests run with the app module as working directory; skip
        // with a reason if the tree layout ever changes.
        val file = File("src/main/assets/models-catalog.json")
        assumeTrue("real catalog asset not found at ${file.absolutePath}", file.isFile)
        var cached = realCatalog
        if (cached == null) {
            cached = ProviderCatalog.parse(file.readText())
            realCatalog = cached
        }
        return cached
    }

    @Test
    fun `real asset contains the full generated provider set`() {
        val catalog = realAsset()
        assertEquals(26, catalog.providers.size)
        // Model counts drift with every upstream pi refresh; assert structure
        // and known entries instead of pinning totals.
        assertTrue(catalog.providers.all { it.models.isNotEmpty() })
        assertTrue(catalog.providers.sumOf { it.models.size } > 500)
        assertNull(catalog.getProvider("not-a-provider"))
        assertNull(catalog.getModel("zai", "not-a-model"))
        assertEquals("cf-aig-authorization", catalog.getProvider("cloudflare-ai-gateway")!!.bearerHeaderName)
        // Parsing already proved every compat field maps; sanity-check a couple
        // of the special shapes made it through.
        val kimi = catalog.getModel("baseten", "moonshotai/Kimi-K2.5")!!
        assertTrue(kimi.reasoning)
        assertIs<ChatTemplateKwargValue.Ref>(kimi.compat.chatTemplateArgs["enable_thinking"])
        val copilot = catalog.getModel("github-copilot", "gpt-4.1")!!
        assertTrue("User-Agent" in copilot.headers)
    }
}
