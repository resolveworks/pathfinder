package com.aletheia.ai.models

import com.aletheia.ai.api.ChatApi
import com.aletheia.ai.core.AssistantMessage
import com.aletheia.ai.core.AssistantMessageEvent
import com.aletheia.ai.core.Context
import com.aletheia.ai.core.Model
import com.aletheia.ai.core.SimpleStreamOptions
import com.aletheia.ai.core.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused registry tests for credential resolution: resolver failures become
 * a terminal Error event, cancellation propagates, and explicit keys bypass
 * the resolver.
 */
class ModelsTest {
    private fun model() = Model(
        id = "m1",
        name = "Model One",
        api = "openai-completions",
        provider = "prov",
        baseUrl = "https://example.test",
    )

    private class RecordingApi : ChatApi {
        var lastApiKey: String? = null
        var calls = 0

        override fun stream(model: Model, context: Context, options: com.aletheia.ai.core.OpenAiCompletionsOptions): Flow<AssistantMessageEvent> =
            flow {
                calls += 1
                lastApiKey = options.apiKey
                val done = AssistantMessage(
                    content = emptyList(),
                    api = model.api,
                    provider = model.provider,
                    model = model.id,
                    stopReason = StopReason.STOP,
                )
                emit(AssistantMessageEvent.Done(done.stopReason, done))
            }
    }

    @Test
    fun resolverFailureEmitsSingleErrorEvent() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                Provider(
                    id = "prov",
                    name = "Provider",
                    baseUrl = "https://example.test",
                    apiKeyResolver = { throw IllegalStateException("keystore exploded") },
                    models = listOf(model()),
                    api = api,
                ),
            ),
        )

        val events = registry.stream("prov", "m1", Context(messages = emptyList())).toList()

        assertEquals(1, events.size)
        val error = events.single() as AssistantMessageEvent.Error
        assertEquals(StopReason.ERROR, error.reason)
        assertEquals("openai-completions", error.error.api)
        assertEquals("prov", error.error.provider)
        assertEquals("m1", error.error.model)
        assertEquals(StopReason.ERROR, error.error.stopReason)
        assertTrue(error.error.timestamp > 0)
        // Safe generic message: no exception text.
        val message = error.error.errorMessage
        assertTrue(message!!.contains("Failed to resolve stored API key"))
        assertTrue(!message.contains("keystore exploded"))
        assertEquals(0, api.calls)
    }

    @Test
    fun resolverCancellationPropagatesWithoutError() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                Provider(
                    id = "prov",
                    name = "Provider",
                    baseUrl = "https://example.test",
                    apiKeyResolver = { throw CancellationException() },
                    models = listOf(model()),
                    api = api,
                ),
            ),
        )

        var thrown: Exception? = null
        try {
            registry.stream("prov", "m1", Context(messages = emptyList())).toList()
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
        assertEquals(0, api.calls)
    }

    @Test
    fun explicitApiKeyBypassesResolver() = runTest {
        var resolverCalls = 0
        val api = RecordingApi()
        val registry = Models(
            listOf(
                Provider(
                    id = "prov",
                    name = "Provider",
                    baseUrl = "https://example.test",
                    apiKeyResolver = {
                        resolverCalls += 1
                        "resolved-key"
                    },
                    models = listOf(model()),
                    api = api,
                ),
            ),
        )

        val events =
            registry.stream("prov", "m1", Context(messages = emptyList()), SimpleStreamOptions(apiKey = "explicit"))
                .toList()

        assertEquals(0, resolverCalls)
        assertEquals(1, api.calls)
        assertEquals("explicit", api.lastApiKey)
        assertTrue(events.single() is AssistantMessageEvent.Done)
    }
}
