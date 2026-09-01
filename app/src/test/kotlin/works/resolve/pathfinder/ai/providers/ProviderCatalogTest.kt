package works.resolve.pathfinder.ai.providers

import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.TestCatalogs
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.CacheControlFormat
import works.resolve.pathfinder.ai.core.ChatTemplateKwargValue
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.DeferredToolsMode
import works.resolve.pathfinder.ai.core.MaxTokensField
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.ThinkingFormat
import works.resolve.pathfinder.ai.core.ThinkingLevelMap
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
    fun `toResolvedAuth maps credentials to normal api keys or resolved headers`() {
        val cf = ProviderCatalog.parse(
            """
            {
              "providers": [
                {
                  "id": "cf",
                  "name": "Cloudflare",
                  "baseUrl": "https://gateway.test/v1",
                  "bearerHeaderName": "cf-aig-authorization",
                  "models": []
                }
              ]
            }
            """,
        ).getProvider("cf")!!

        // Cloudflare AI Gateway: header auth only, no default apiKey path.
        val cfAuth = cf.toResolvedAuth("cf-key", mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct"))
        assertNull(cfAuth.apiKey)
        assertEquals(
            mapOf(
                "cf-aig-authorization" to "Bearer cf-key",
                "Authorization" to null,
                "x-api-key" to null,
            ),
            cfAuth.headers,
        )
        assertEquals(mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct"), cfAuth.env)

        // Ordinary providers: plain apiKey auth with no headers.
        val plain = ProviderCatalog.parse(
            """
            {
              "providers": [{"id": "zai", "name": "ZAI", "baseUrl": "https://z.test", "models": []}]
            }
            """,
        ).getProvider("zai")!!
        val plainAuth = plain.toResolvedAuth("sk-key", emptyMap())
        assertEquals("sk-key", plainAuth.apiKey)
        assertEquals(emptyMap(), plainAuth.headers)
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
        assertTrue(compat.supportsStrictMode)
        assertEquals(CacheControlFormat.ANTHROPIC, compat.cacheControlFormat)
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
    fun `completions cacheControlFormat parses and openrouter anthropic detection applies`() {
        // pi detectCompat (openai-completions.ts:1621,1633): "anthropic" when
        // the provider is openrouter and the model id starts with "anthropic/";
        // getCompat merges an explicit model.compat override on top (:1701).
        val catalog = ProviderCatalog.parse(
            """
            {
              "providers": [
                {
                  "id": "openrouter", "name": "OR", "baseUrl": "https://openrouter.ai/api/v1",
                  "models": [
                    {"id": "anthropic/claude-sonnet-4", "name": "A"},
                    {"id": "openai/gpt-4o", "name": "B"},
                    {
                      "id": "qwen/qwen3", "name": "C",
                      "compat": {"cacheControlFormat": "anthropic"}
                    }
                  ]
                },
                {
                  "id": "other", "name": "O", "baseUrl": "https://other.test/v1",
                  "models": [{"id": "anthropic/claude-sonnet-4", "name": "D"}]
                }
              ]
            }
            """,
        )
        assertEquals(CacheControlFormat.ANTHROPIC, catalog.getModel("openrouter", "anthropic/claude-sonnet-4")!!.compat.cacheControlFormat, "openrouter anthropic/* detected")
        assertNull(catalog.getModel("openrouter", "openai/gpt-4o")!!.compat.cacheControlFormat)
        assertEquals(CacheControlFormat.ANTHROPIC, catalog.getModel("openrouter", "qwen/qwen3")!!.compat.cacheControlFormat, "explicit compat overrides detection")
        assertNull(catalog.getModel("other", "anthropic/claude-sonnet-4")!!.compat.cacheControlFormat, "non-openrouter anthropic model unaffected")
    }

    @Test
    fun `openrouter anthropic models keep anthropic cache control format from the real asset`() {
        // pi openrouter-cache-control-models.test.ts: the ~anthropic/*-latest
        // aliases (explicit catalog compat) and the anthropic/* models both
        // resolve cacheControlFormat "anthropic".
        val catalog = realAsset()
        for (id in listOf(
            "~anthropic/claude-fable-latest",
            "~anthropic/claude-haiku-latest",
            "~anthropic/claude-opus-latest",
            "~anthropic/claude-sonnet-latest",
        )) {
            assertEquals(CacheControlFormat.ANTHROPIC, catalog.getModel("openrouter", id)!!.compat.cacheControlFormat)
        }
        assertEquals(CacheControlFormat.ANTHROPIC, catalog.getModel("openrouter", "anthropic/claude-sonnet-4")!!.compat.cacheControlFormat)
        assertNull(catalog.getModel("openrouter", "aion-labs/aion-2.0")!!.compat.cacheControlFormat)
    }

    @Test
    fun `supportsMaxOutputTokens defaults true and parses false for responses models (pi b8b873b98, #8941)`() {
        val catalog = ProviderCatalog.parse(
            """
            {
              "providers": [{
                "id": "p", "name": "P", "baseUrl": "https://p.test/v1",
                "models": [
                  {"id": "a", "name": "A", "api": "openai-responses",
                   "compat": {"supportsMaxOutputTokens": false}},
                  {"id": "b", "name": "B", "api": "openai-responses"},
                  {"id": "c", "name": "C",
                   "compat": {"requiresReasoningContentOnAssistantMessages": true,
                               "deferredToolsMode": "kimi"}}
                ]
              }]
            }
            """,
        )
        assertEquals(false, catalog.getModel("p", "a")!!.responsesCompat?.supportsMaxOutputTokens)
        assertEquals(true, catalog.getModel("p", "b")!!.responsesCompat?.supportsMaxOutputTokens)
        assertTrue(catalog.getModel("p", "c")!!.compat.requiresReasoningContentOnAssistantMessages)
        assertEquals(DeferredToolsMode.KIMI, catalog.getModel("p", "c")!!.compat.deferredToolsMode)
        assertNull(catalog.getModel("p", "a")!!.compat.deferredToolsMode)
    }

    @Test
    fun `unknown deferredToolsMode value fails fast with context`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ProviderCatalog.parse(
                """
                {
                  "providers": [{
                    "id": "p", "name": "P", "baseUrl": "https://p.test/v1",
                    "models": [{"id": "a", "name": "A", "compat": {"deferredToolsMode": "anthropic"}}]
                  }]
                }
                """,
            )
        }
        assertTrue("deferred tools mode" in (error.message ?: ""))
    }

    @Test
    fun `unknown compat keys fail at parse instead of being dropped silently`() {
        // pi's compat surface grows flags over time (supportsMaxOutputTokens
        // being the latest); the generator emits pi's compat verbatim, so an
        // unknown key means upstream drift that must fail loudly, not
        // silently disable the behavior (this is what hid the responses
        // max_output_tokens gate).
        val error = assertFailsWith<IllegalArgumentException> {
            ProviderCatalog.parse(
                """
                {
                  "providers": [{
                    "id": "p", "name": "P", "baseUrl": "https://p.test/v1",
                    "models": [
                      {"id": "a", "name": "A"},
                      {"id": "b", "name": "B", "compat": {"supportsToolReferences": true}}
                    ]
                  }]
                }
                """,
            )
        }
        assertTrue("supportsToolReferences" in (error.message ?: ""))
        assertTrue("p/b" in (error.message ?: ""))
    }

    @Test
    fun `real asset compat keys are all modeled by CompatDto`() {
        // Guards the generator/DTO lockstep: every compat key the current
        // bundled catalog carries parses without the unknown-key rejection
        // firing (and every deferredToolsMode/reasoning-content model is
        // plumbed).
        val catalog = realAsset()
        var deferred = 0
        var requiresReasoningContent = 0
        var supportsMaxOutputTokensFalse = 0
        for (provider in catalog.providers) {
            for (model in provider.models) {
                if (model.compat.deferredToolsMode == DeferredToolsMode.KIMI) deferred++
                if (model.compat.requiresReasoningContentOnAssistantMessages) requiresReasoningContent++
                if (model.responsesCompat?.supportsMaxOutputTokens == false) supportsMaxOutputTokensFalse++
            }
        }
        assertTrue(deferred > 0, "expected kimi deferredToolsMode models in the asset")
        assertTrue(requiresReasoningContent > 0, "expected requiresReasoningContentOnAssistantMessages models")
        assertEquals(0, supportsMaxOutputTokensFalse)
    }

    @Test
    fun `completions supportsStrictMode defaults true and parses false from the catalog`() {
        // pi openai-completions getCompat (:1653,:1699): detected default true;
        // the catalog marks moonshotai/together/nvidia/cloudflare-ai-gateway false.
        val catalog = ProviderCatalog.parse(
            """
            {
              "providers": [{
                "id": "p", "name": "P", "baseUrl": "https://p.test/v1",
                "models": [
                  {"id": "a", "name": "A"},
                  {"id": "b", "name": "B", "compat": {"supportsStrictMode": false}}
                ]
              }]
            }
            """,
        )
        assertTrue(catalog.getModel("p", "a")!!.compat.supportsStrictMode)
        assertFalse(catalog.getModel("p", "b")!!.compat.supportsStrictMode)
    }

    @Test
    fun `supportsOpenAIGrammarTools defaults false and parses true for responses and completions`() {
        // pi: responses getCompat (openai-responses.ts:74) and completions
        // detectCompat (openai-completions.ts:1654, :1700) both default false;
        // the generated catalog enables it for capable models.
        val catalog = ProviderCatalog.parse(
            """
            {
              "providers": [{
                "id": "p", "name": "P", "baseUrl": "https://p.test/v1",
                "models": [
                  {"id": "a", "name": "A", "api": "openai-responses",
                   "compat": {"supportsOpenAIGrammarTools": true}},
                  {"id": "b", "name": "B", "api": "openai-responses"},
                  {"id": "c", "name": "C",
                   "compat": {"supportsOpenAIGrammarTools": true}},
                  {"id": "d", "name": "D"}
                ]
              }]
            }
            """,
        )
        assertEquals(true, catalog.getModel("p", "a")!!.responsesCompat?.supportsOpenAIGrammarTools)
        assertEquals(false, catalog.getModel("p", "b")!!.responsesCompat?.supportsOpenAIGrammarTools)
        assertEquals(true, catalog.getModel("p", "c")!!.compat.supportsOpenAIGrammarTools)
        assertEquals(false, catalog.getModel("p", "d")!!.compat.supportsOpenAIGrammarTools)
    }

    @Test
    fun `anthropic supportsStrictTools defaults false and parses true from the catalog`() {
        // pi getAnthropicCompat (anthropic-messages.ts:193):
        // `model.compat?.supportsStrictTools ?? false`.
        val catalog = ProviderCatalog.parse(
            """
            {"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
              {"id":"a","name":"A","api":"anthropic-messages",
               "compat":{"supportsStrictTools": true}},
              {"id":"b","name":"B","api":"anthropic-messages"}
            ]}]}
            """,
        )
        assertEquals(true, catalog.getModel("p", "a")!!.anthropicCompat.supportsStrictTools)
        assertEquals(false, catalog.getModel("p", "b")!!.anthropicCompat.supportsStrictTools)
    }

    @Test
    fun `anthropic allowedFallbackModels decode with costs and default to empty`() {
        // pi types.ts:307-311,695-702: AnthropicAllowedFallbackModel list with
        // local pricing metadata; absent means callers must omit `fallbacks`.
        val catalog = ProviderCatalog.parse(
            """
            {"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
              {"id":"a","name":"A","api":"anthropic-messages",
               "compat":{"allowedFallbackModels":[{"provider":"p","model":"b","cost":{"input":5,"output":25,"cacheRead":0.5,"cacheWrite":6.25}}]}},
              {"id":"b","name":"B","api":"anthropic-messages"}
            ]}]}
            """,
        )
        val fallbacks = catalog.getModel("p", "a")!!.anthropicCompat.allowedFallbackModels
        assertEquals(1, fallbacks.size)
        assertEquals("p", fallbacks[0].provider)
        assertEquals("b", fallbacks[0].model)
        assertEquals(5.0, fallbacks[0].cost.input)
        assertEquals(6.25, fallbacks[0].cost.cacheWrite)
        assertEquals(0, catalog.getModel("p", "b")!!.anthropicCompat.allowedFallbackModels.size)
        // Real asset round-trip: pi's data carries the list on claude-fable-5
        // and claude-opus-5 only.
        val fable = realAsset().getModel("anthropic", "claude-fable-5")!!
        assertTrue(fable.anthropicCompat.allowedFallbackModels.size > 0)
        assertEquals(
            listOf("claude-opus-4-8", "claude-opus-5"),
            fable.anthropicCompat.allowedFallbackModels.map { it.model },
        )
        assertTrue(
            realAsset().getModel("anthropic", "claude-opus-5")!!.anthropicCompat.allowedFallbackModels.size > 0,
        )
        assertTrue(
            realAsset().getModel("anthropic", "claude-sonnet-5")!!.anthropicCompat.allowedFallbackModels.isEmpty(),
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

    // ---- credential completeness ----

    @Test
    fun `cloudflare credential is incomplete without every auth prompt value`() {
        val provider = TestCatalogs.CLOUDFLARE
        // Key only: account/gateway ids are still missing.
        assertFalse(provider.isCredentialComplete("cf-key", emptyMap()))
        assertEquals(
            listOf("CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_GATEWAY_ID"),
            provider.missingAuthPrompts("cf-key", emptyMap()).map { it.envKey },
        )
        // Gateway id still missing; blank values count as missing.
        assertFalse(provider.isCredentialComplete("cf-key", mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc")))
        assertFalse(
            provider.isCredentialComplete(
                "cf-key",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "  "),
            ),
        )
        // No key at all (first prompt): incomplete even with both env ids.
        assertFalse(
            provider.isCredentialComplete(
                null,
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
            ),
        )
    }

    @Test
    fun `credential is complete when every auth prompt has a value`() {
        val provider = TestCatalogs.CLOUDFLARE
        assertTrue(
            provider.isCredentialComplete(
                "cf-key",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
            ),
        )
        assertTrue(provider.missingAuthPrompts("cf-key", mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw")).isEmpty())
        // A single-prompt provider (zai) is complete with just the key.
        assertTrue(TestCatalogs.ZAI.isCredentialComplete("zai-key", emptyMap()))
        assertFalse(TestCatalogs.ZAI.isCredentialComplete(null, emptyMap()))
    }

    @Test
    fun `provider with no auth prompts still requires a key`() {
        val provider = ProviderCatalog.parse(
            """{"providers":[{"id":"p","name":"P","baseUrl":"u","models":[]}]}""",
        ).getProvider("p")!!
        assertFalse(provider.isCredentialComplete(null, emptyMap()))
        assertFalse(provider.isCredentialComplete("  ", emptyMap()))
        assertTrue(provider.isCredentialComplete("k", emptyMap()))
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
            cost = works.resolve.pathfinder.ai.core.ModelCost(
                input = 0.6,
                output = 2.2,
                cacheRead = 0.11,
                cacheWrite = 0.0,
            ),
            contextWindow = 204_800,
            maxTokens = 131_072,
            compat = works.resolve.pathfinder.ai.core.OpenAiCompletionsCompat(
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

    // ---- anthropic-messages compat mapping ----

    @Test
    fun `compat anthropic flags map with pi defaults when absent`() {
        val model = ProviderCatalog.parse(
            """{"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
               {"id":"m","name":"M","api":"anthropic-messages"}]}]}""",
        ).getModel("p", "m")!!
        assertEquals(works.resolve.pathfinder.ai.core.AnthropicMessagesCompat(), model.anthropicCompat)
    }

    @Test
    fun `compat anthropic flags map explicit non-default values`() {
        val model = ProviderCatalog.parse(
            """
            {"providers":[{"id":"p","name":"P","baseUrl":"u","models":[
              {"id":"m","name":"M","api":"anthropic-messages","compat":{
                "supportsEagerToolInputStreaming": false,
                "supportsLongCacheRetention": false,
                "sendSessionAffinityHeaders": true,
                "supportsCacheControlOnTools": false,
                "supportsTemperature": false,
                "allowEmptySignature": true,
                "supportsStrictTools": true,
                "forceAdaptiveThinking": true
              }}]}]}
            """,
        ).getModel("p", "m")!!
        assertEquals(
            works.resolve.pathfinder.ai.core.AnthropicMessagesCompat(
                supportsEagerToolInputStreaming = false,
                supportsLongCacheRetention = false,
                sendSessionAffinityHeaders = true,
                supportsCacheControlOnTools = false,
                supportsTemperature = false,
                allowEmptySignature = true,
                supportsStrictTools = true,
                forceAdaptiveThinking = true,
            ),
            model.anthropicCompat,
        )
    }

    @Test
    fun `real asset loads non-default anthropic compat flags`() {
        val catalog = realAsset()
        // anthropic/claude-opus-4-7: strict tools + adaptive thinking + no
        // temperature; everything else stays at pi defaults.
        assertEquals(
            works.resolve.pathfinder.ai.core.AnthropicMessagesCompat(
                supportsTemperature = false,
                supportsStrictTools = true,
                forceAdaptiveThinking = true,
            ),
            catalog.getModel("anthropic", "claude-opus-4-7")!!.anthropicCompat,
        )
        // fireworks deepseek-v4-flash: session affinity on, everything else off.
        assertEquals(
            works.resolve.pathfinder.ai.core.AnthropicMessagesCompat(
                supportsEagerToolInputStreaming = false,
                supportsLongCacheRetention = false,
                sendSessionAffinityHeaders = true,
                supportsCacheControlOnTools = false,
            ),
            catalog.getModel("fireworks", "accounts/fireworks/models/glm-5p3")!!.anthropicCompat,
        )
    }

    @Test
    fun `responses compat parses from the catalog asset`() {
        val catalog = realAsset()
        val openai = catalog.getModel("openai", "gpt-4")!!
        assertEquals(true, openai.responsesCompat?.supportsStrictMode)
        val opencode = catalog.getModel("opencode", "gpt-5")!!
        assertEquals(
            works.resolve.pathfinder.ai.core.SessionAffinityFormat.OPENAI_NOSESSION,
            opencode.responsesCompat?.sessionAffinityFormat,
        )
        val xai = catalog.getModel("xai", "grok-4.3")!!
        assertEquals(false, xai.responsesCompat?.supportsLongCacheRetention)
        // Only the Responses family carries responsesCompat.
        val claude = catalog.getModel("anthropic", catalog.getProvider("anthropic")!!.models.first { it.api == "anthropic-messages" }.id)!!
        assertNull(claude.responsesCompat)
    }

    @Test
    fun `completions compat parses affinity and cache retention flags`() {
        val catalog = realAsset()
        // cloudflare-ai-gateway workers-ai model: affinity on, long retention off.
        val cf = catalog.getModel("cloudflare-ai-gateway", "workers-ai/@cf/google/gemma-4-26b-a4b-it")!!
        assertEquals(true, cf.compat.sendSessionAffinityHeaders)
        assertEquals(false, cf.compat.supportsLongCacheRetention)
        assertNull(cf.compat.sessionAffinityFormat, "format auto-detects at request time")
        // openrouter models keep pi defaults: affinity off, format auto-detect, retention supported.
        val or = catalog.getModel("openrouter", "aion-labs/aion-2.0")!!
        assertEquals(false, or.compat.sendSessionAffinityHeaders)
        assertEquals(true, or.compat.supportsLongCacheRetention)
        assertNull(or.compat.sessionAffinityFormat)
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
        assertEquals(37, catalog.providers.size)
        // Model counts drift with every upstream pi refresh; assert structure
        // and known entries instead of pinning totals.
        assertTrue(catalog.providers.all { it.models.isNotEmpty() })
        assertTrue(catalog.providers.sumOf { it.models.size } > 1100)
        assertNull(catalog.getProvider("not-a-provider"))
        assertNull(catalog.getProvider("amazon-bedrock"))
        assertNull(catalog.getProvider("google-vertex"))
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

    @Test
    fun `real asset spans all pi model APIs`() {
        val catalog = realAsset()
        val apis = catalog.providers.flatMapTo(mutableSetOf()) { it.apis }
        assertEquals(
            setOf(
                "anthropic-messages",
                "azure-openai-responses",
                "google-generative-ai",
                "mistral-conversations",
                "openai-codex-responses",
                "openai-completions",
                "openai-responses",
            ),
            apis,
        )
        // Mixed-API providers keep every model (pi's Record<ApiId, Api>).
        assertEquals(
            setOf("anthropic-messages", "openai-completions", "openai-responses"),
            catalog.getProvider("cloudflare-ai-gateway")!!.apis,
        )
        assertEquals(setOf("openai-responses"), catalog.getProvider("openai")!!.apis)
        assertEquals(setOf("openai-completions"), catalog.getProvider("zai")!!.apis)
    }

    @Test
    fun `real asset auth metadata covers new providers`() {
        val catalog = realAsset()
        // OAuth-only provider: models kept, auth carries OAuth capability
        // metadata only — no API-key prompts, no placeholder env key.
        val codex = catalog.getProvider("openai-codex")!!
        assertTrue(codex.models.isNotEmpty())
        assertEquals(emptyList<AuthPrompt>(), codex.auth.prompts)
        assertEquals(
            ProviderOAuth(name = "OpenAI (ChatGPT Plus/Pro)", isSubscription = true),
            codex.auth.oauth,
        )
        // Simple env-key providers.
        assertEquals("ANTHROPIC_API_KEY", catalog.getProvider("anthropic")!!.auth.prompts.single().envKey)
        assertEquals("GEMINI_API_KEY", catalog.getProvider("google")!!.auth.prompts.single().envKey)
        // Non-secret extra prompts (Cloudflare account/gateway).
        val gateway = catalog.getProvider("cloudflare-ai-gateway")!!.auth.prompts
        assertEquals(
            listOf("CLOUDFLARE_API_KEY", "CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_GATEWAY_ID"),
            gateway.map { it.envKey },
        )
        assertFalse(gateway[1].secret)
    }

    @Test
    fun `real asset keeps OAuth capability metadata for the six OAuth providers`() {
        val catalog = realAsset()
        val expected = mapOf(
            "anthropic" to ProviderOAuth("Anthropic (Claude Pro/Max)", isSubscription = true),
            "github-copilot" to ProviderOAuth("GitHub Copilot", isSubscription = true),
            "kimi-coding" to ProviderOAuth(
                "Kimi Code (subscription)",
                loginLabel = "Sign in with Kimi Code",
                isSubscription = true,
            ),
            "openai-codex" to ProviderOAuth("OpenAI (ChatGPT Plus/Pro)", isSubscription = true),
            "openrouter" to ProviderOAuth("OpenRouter OAuth", loginLabel = "Sign in with OpenRouter"),
            "xai" to ProviderOAuth(
                "xAI (Grok/X subscription)",
                loginLabel = "Sign in with SuperGrok or X Premium",
                isSubscription = true,
            ),
        )
        for (provider in catalog.providers) {
            assertEquals(expected[provider.id], provider.auth.oauth)
        }
        // OAuth-capable providers other than openai-codex keep their API-key
        // prompt shape alongside the OAuth metadata.
        for (id in expected.keys - "openai-codex") {
            assertTrue(catalog.getProvider(id)!!.auth.prompts.isNotEmpty())
        }
    }

    @Test
    fun `mixed api provider runtime dispatch registers only implemented apis`() {
        val catalog = realAsset()
        val entry = catalog.getProvider("cloudflare-ai-gateway")!!
        val runtime = entry.toRuntimeProvider(FakeTransport())
        // Only APIs without a Kotlin port are excluded from the runtime api
        // map; openai-responses now streams like the rest.
        assertEquals(setOf("openai-completions", "anthropic-messages", "openai-responses"), runtime.apis.keys)
        val completionsModel = entry.models.first { it.api == "openai-completions" }
        val models = Models(listOf(runtime))
        assertEquals(completionsModel.id, models.getModel("cloudflare-ai-gateway", completionsModel.id)!!.id)
    }
}
