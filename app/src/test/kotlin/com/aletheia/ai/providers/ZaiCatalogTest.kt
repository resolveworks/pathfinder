package com.aletheia.ai.providers

import com.aletheia.ai.core.InputModality
import com.aletheia.ai.core.MaxTokensField
import com.aletheia.ai.core.ModelThinkingLevel
import com.aletheia.ai.core.ThinkingFormat
import com.aletheia.ai.core.clampThinkingLevel
import com.aletheia.ai.core.getSupportedThinkingLevels
import com.aletheia.ai.transport.HttpStreamingTransport
import com.aletheia.ai.transport.TransportRequest
import com.aletheia.ai.transport.TransportResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class ZaiCatalogTest {

    @Test
    fun `catalog contains the current zai-coding-plan models`() {
        assertEquals(
            listOf("glm-4.7", "glm-5-turbo", "glm-5.3", "glm-5.2", "glm-5.2-highspeed"),
            ZaiModels.ALL.map { it.id },
        )
    }

    @Test
    fun `shared metadata is ported from models dev`() {
        for (model in ZaiModels.ALL) {
            assertEquals("zai", model.provider)
            assertEquals("openai-completions", model.api)
            assertEquals("https://api.z.ai/api/coding/paas/v4", model.baseUrl)
            assertTrue(model.reasoning, "${model.id} must be reasoning-capable")
            assertEquals(listOf(InputModality.TEXT), model.input)
            assertEquals(131_072, model.maxTokens, "${model.id} output limit")
            assertFalse(model.compat.supportsStore)
            assertFalse(model.compat.supportsDeveloperRole)
            assertEquals(MaxTokensField.MAX_TOKENS, model.compat.maxTokensField)
            assertEquals(ThinkingFormat.ZAI, model.compat.thinkingFormat)
            assertTrue(model.compat.zaiToolStream, "${model.id} supports tool_stream")
            assertTrue(model.compat.supportsUsageInStreaming)
        }
    }

    @Test
    fun `context windows match the source`() {
        assertEquals(204_800, ZaiModels.GLM_4_7.contextWindow)
        assertEquals(200_000, ZaiModels.GLM_5_TURBO.contextWindow)
        assertEquals(1_000_000, ZaiModels.GLM_5_3.contextWindow)
        assertEquals(1_000_000, ZaiModels.GLM_5_2.contextWindow)
        assertEquals(1_000_000, ZaiModels.GLM_5_2_HIGHSPEED.contextWindow)
    }

    @Test
    fun `reference costs match the zai source where present`() {
        assertEquals(0.6, ZaiModels.GLM_4_7.cost.input)
        assertEquals(2.2, ZaiModels.GLM_4_7.cost.output)
        assertEquals(0.11, ZaiModels.GLM_4_7.cost.cacheRead)
        assertEquals(1.2, ZaiModels.GLM_5_TURBO.cost.input)
        assertEquals(4.0, ZaiModels.GLM_5_TURBO.cost.output)
        assertEquals(1.4, ZaiModels.GLM_5_2.cost.input)
        assertEquals(4.4, ZaiModels.GLM_5_2.cost.output)
        assertEquals(0.26, ZaiModels.GLM_5_2.cost.cacheRead)
        // No pay-as-you-go reference entry: zero, matching pi's generator.
        assertEquals(0.0, ZaiModels.GLM_5_3.cost.input)
        assertEquals(0.0, ZaiModels.GLM_5_2_HIGHSPEED.cost.input)
    }

    @Test
    fun `toggle models have no thinking level map`() {
        assertNull(ZaiModels.GLM_4_7.thinkingLevelMap)
        assertNull(ZaiModels.GLM_5_TURBO.thinkingLevelMap)
        assertFalse(ZaiModels.GLM_4_7.compat.supportsReasoningEffort)
    }

    @Test
    fun `effort models expose level maps with explicit nulls`() {
        // glm-5.3: effort [low, high, max]; off/minimal/medium/xhigh explicitly null.
        val map53 = ZaiModels.GLM_5_3.thinkingLevelMap!!
        assertEquals("low", map53.forLevel(ModelThinkingLevel.LOW))
        assertEquals("high", map53.forLevel(ModelThinkingLevel.HIGH))
        assertEquals("max", map53.forLevel(ModelThinkingLevel.MAX))
        assertNull(map53.forLevel(ModelThinkingLevel.OFF))
        assertTrue(map53.isSpecified(ModelThinkingLevel.OFF), "off must be explicitly unsupported")
        assertTrue(map53.isSpecified(ModelThinkingLevel.MINIMAL))
        assertNull(map53.forLevel(ModelThinkingLevel.MINIMAL))
        assertTrue(ZaiModels.GLM_5_3.compat.supportsReasoningEffort)

        // glm-5.2*: effort [high, max] with off mapped to "none"
        val map52 = ZaiModels.GLM_5_2.thinkingLevelMap!!
        assertEquals("none", map52.forLevel(ModelThinkingLevel.OFF))
        assertEquals("high", map52.forLevel(ModelThinkingLevel.HIGH))
        assertEquals("max", map52.forLevel(ModelThinkingLevel.MAX))
        assertNull(map52.forLevel(ModelThinkingLevel.LOW))
        assertTrue(map52.isSpecified(ModelThinkingLevel.LOW))
    }

    @Test
    fun `supported levels follow pi defaults and explicit-null semantics`() {
        // Reasoning model without a map: OFF..HIGH default-supported, XHIGH/MAX not.
        assertEquals(
            listOf(
                ModelThinkingLevel.OFF,
                ModelThinkingLevel.MINIMAL,
                ModelThinkingLevel.LOW,
                ModelThinkingLevel.MEDIUM,
                ModelThinkingLevel.HIGH,
            ),
            getSupportedThinkingLevels(ZaiModels.GLM_4_7),
        )
        // Explicit null entries remove levels.
        assertEquals(
            listOf(ModelThinkingLevel.LOW, ModelThinkingLevel.HIGH, ModelThinkingLevel.MAX),
            getSupportedThinkingLevels(ZaiModels.GLM_5_3),
        )
        assertEquals(
            listOf(ModelThinkingLevel.OFF, ModelThinkingLevel.HIGH, ModelThinkingLevel.MAX),
            getSupportedThinkingLevels(ZaiModels.GLM_5_2),
        )
    }

    @Test
    fun `clamping rounds up then down like pi`() {
        // glm-5.3 supports low/high/max; medium rounds up to high.
        assertEquals(
            ModelThinkingLevel.HIGH,
            clampThinkingLevel(ZaiModels.GLM_5_3, ModelThinkingLevel.MEDIUM),
        )
        // minimal rounds up to low.
        assertEquals(
            ModelThinkingLevel.LOW,
            clampThinkingLevel(ZaiModels.GLM_5_3, ModelThinkingLevel.MINIMAL),
        )
        // glm-5.2 supports off/high/max; low rounds up to high.
        assertEquals(
            ModelThinkingLevel.HIGH,
            clampThinkingLevel(ZaiModels.GLM_5_2, ModelThinkingLevel.LOW),
        )
        // No-map model: XHIGH clamps down to HIGH (default supported).
        assertEquals(
            ModelThinkingLevel.HIGH,
            clampThinkingLevel(ZaiModels.GLM_4_7, ModelThinkingLevel.XHIGH),
        )
    }

    @Test
    fun `provider factory wires identity catalog and api`() {
        val provider = ZaiProvider.create(
            object : HttpStreamingTransport {
                override suspend fun post(request: TransportRequest): TransportResponse =
                    TransportResponse(200, emptyMap(), flowOf())
            },
            apiKeyResolver = { "stored" },
        )
        assertEquals("zai", provider.id)
        assertEquals("Z.AI", provider.name)
        assertEquals(ZaiModels.BASE_URL, provider.baseUrl)
        assertEquals(ZaiModels.ALL, provider.models)
        assertTrue(provider.api is com.aletheia.ai.api.OpenAiCompletionsApi)
    }

    @Test
    fun `factory default base url is the catalog default without rebasing models`() {
        val provider = ZaiProvider.create(
            object : HttpStreamingTransport {
                override suspend fun post(request: TransportRequest): TransportResponse =
                    TransportResponse(200, emptyMap(), flowOf())
            },
        )
        assertEquals(ZaiModels.BASE_URL, provider.baseUrl)
        // The shared immutable catalog is reused unchanged.
        assertEquals(ZaiModels.ALL, provider.models)
    }

    @Test
    fun `factory override rebases provider and every model`() {
        val provider = ZaiProvider.create(
            object : HttpStreamingTransport {
                override suspend fun post(request: TransportRequest): TransportResponse =
                    TransportResponse(200, emptyMap(), flowOf())
            },
            baseUrl = " http://localhost:1234/v4/ ",
        )
        assertEquals("http://localhost:1234/v4", provider.baseUrl)
        assertEquals(ZaiModels.ALL.size, provider.models.size)
        assertTrue(provider.models.all { it.baseUrl == "http://localhost:1234/v4" })
        // The shared catalog is not mutated.
        assertTrue(ZaiModels.ALL.all { it.baseUrl == ZaiModels.BASE_URL })
    }

    @Test
    fun `factory rejects blank base url`() {
        val transport = object : HttpStreamingTransport {
            override suspend fun post(request: TransportRequest): TransportResponse =
                TransportResponse(200, emptyMap(), flowOf())
        }
        assertFailsWith<IllegalArgumentException> { ZaiProvider.create(transport, baseUrl = "   ") }
        assertFailsWith<IllegalArgumentException> { ZaiProvider.create(transport, baseUrl = "") }
        assertFailsWith<IllegalArgumentException> { ZaiProvider.create(transport, baseUrl = "/") }
    }
}
