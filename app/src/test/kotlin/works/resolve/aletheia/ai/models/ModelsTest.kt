package works.resolve.aletheia.ai.models

import works.resolve.aletheia.ai.api.ChatApi
import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.OpenAiCompletionsOptions
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.testing.TestCatalogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused registry tests for auth resolution: resolver failures and missing
 * credentials become a terminal Error event, cancellation propagates,
 * explicit keys bypass the resolver, and env/bearer-header merging follows
 * pi's applyAuth precedence.
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
        var lastEnv: Map<String, String> = emptyMap()
        var lastHeaders: Map<String, String?> = emptyMap()
        var calls = 0

        override fun stream(
            model: Model,
            context: Context,
            options: OpenAiCompletionsOptions,
        ): Flow<AssistantMessageEvent> =
            flow {
                calls += 1
                lastApiKey = options.apiKey
                lastEnv = options.env
                lastHeaders = options.headers
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
                    authResolver = { throw IllegalStateException("keystore exploded") },
                    models = listOf(model()),
                    api = api,
                ),
            ),
        )

        val events = registry.stream(model(), Context(messages = emptyList())).toList()

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
        assertTrue(message!!.contains("Failed to resolve stored credential"))
        assertTrue(!message.contains("keystore exploded"))
        assertEquals(0, api.calls)
    }

    @Test
    fun nullResolutionEmitsSingleErrorEvent() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                Provider(
                    id = "prov",
                    name = "Provider",
                    baseUrl = "https://example.test",
                    authResolver = { null },
                    models = listOf(model()),
                    api = api,
                ),
            ),
        )

        val events = registry.stream(model(), Context(messages = emptyList())).toList()

        assertEquals(1, events.size)
        val error = events.single() as AssistantMessageEvent.Error
        assertEquals(StopReason.ERROR, error.reason)
        assertTrue(error.error.errorMessage!!.contains("Provider 'prov' is not configured"))
        assertEquals(0, api.calls)
    }

    @Test
    fun missingResolverEmitsSingleErrorEvent() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                Provider(
                    id = "prov",
                    name = "Provider",
                    baseUrl = "https://example.test",
                    models = listOf(model()),
                    api = api,
                ),
            ),
        )

        val events = registry.stream(model(), Context(messages = emptyList())).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is AssistantMessageEvent.Error)
        assertEquals(0, api.calls)
    }

    @Test
    fun unknownProviderThrows() {
        val registry = Models(emptyList())
        val alien = model().copy(provider = "alien")
        try {
            registry.stream(alien, Context(messages = emptyList()))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (error: IllegalArgumentException) {
            assertTrue("Unknown provider" in (error.message ?: ""))
        }
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
                    authResolver = { throw CancellationException() },
                    models = listOf(model()),
                    api = api,
                ),
            ),
        )

        var thrown: Exception? = null
        try {
            registry.stream(model(), Context(messages = emptyList())).toList()
        } catch (e: Exception) {
            thrown = e
        }
        assertTrue(thrown is CancellationException)
        assertEquals(0, api.calls)
    }

    @Test
    fun explicitApiKeyIsShapedByResolverWithoutReadingStoredCredentials() = runTest {
        var storeReads = 0
        var resolverKey: String? = null
        val api = RecordingApi()
        val registry = Models(
            listOf(
                Provider(
                    id = "prov",
                    name = "Provider",
                    baseUrl = "https://example.test",
                    authResolver = { explicitKey ->
                        if (explicitKey != null) {
                            // pi's resolveProviderAuth overrides: shape the
                            // explicit key without touching the store.
                            ResolvedAuth(apiKey = explicitKey)
                        } else {
                            storeReads += 1
                            ResolvedAuth("resolved-key")
                        }
                    },
                    models = listOf(model()),
                    api = api,
                ),
            ),
        )

        val events = registry
            .stream(model(), Context(messages = emptyList()), SimpleStreamOptions(apiKey = "explicit"))
            .toList()

        assertEquals(0, storeReads)
        assertEquals(1, api.calls)
        assertEquals("explicit", api.lastApiKey)
        assertTrue(events.single() is AssistantMessageEvent.Done)
    }

    @Test
    fun cloudflareExplicitKeyAndEnvResolveToHeaderAuthOnly() = runTest {
        val entry = TestCatalogs.CLOUDFLARE
        var storeReads = 0
        val api = RecordingApi()
        // Factory-style resolver: explicit keys bypass the store but keep the
        // provider's auth shaping (cf-aig-authorization).
        val authResolver: suspend (String?) -> ResolvedAuth? = { explicitKey ->
            if (explicitKey != null) {
                entry.toResolvedAuth(explicitKey, emptyMap())
            } else {
                storeReads += 1
                null
            }
        }
        val registry = Models(
            listOf(
                Provider(
                    id = entry.id,
                    name = entry.name,
                    baseUrl = entry.baseUrl,
                    authResolver = authResolver,
                    models = entry.models,
                    api = api,
                ),
            ),
        )

        val events = registry.stream(
            entry.models.single(),
            Context(messages = emptyList()),
            SimpleStreamOptions(
                apiKey = "explicit-cf-key",
                env = mapOf(
                    "CLOUDFLARE_ACCOUNT_ID" to "acct-explicit",
                    "CLOUDFLARE_GATEWAY_ID" to "gw-explicit",
                ),
            ),
        ).toList()

        assertEquals(0, storeReads)
        assertTrue(events.single() is AssistantMessageEvent.Done)
        // No apiKey path: the explicit key became a resolved header only.
        assertEquals(null, api.lastApiKey)
        assertEquals(
            mapOf(
                "cf-aig-authorization" to "Bearer explicit-cf-key",
                "Authorization" to null,
                "x-api-key" to null,
            ),
            api.lastHeaders,
        )
        assertEquals(
            mapOf(
                "CLOUDFLARE_ACCOUNT_ID" to "acct-explicit",
                "CLOUDFLARE_GATEWAY_ID" to "gw-explicit",
            ),
            api.lastEnv,
        )
    }

    @Test
    fun resolvedCredentialMergesWithExplicitOptionsUsingPiPrecedence() = runTest {
        val api = RecordingApi()
        val registry = Models(
            listOf(
                Provider(
                    id = "prov",
                    name = "Provider",
                    baseUrl = "https://example.test",
                    authResolver = {
                        ResolvedAuth(
                            apiKey = "resolved-key",
                            env = mapOf("A" to "resolved-a", "B" to "resolved-b"),
                            headers = mapOf(
                                "cf-aig-authorization" to "Bearer resolved-key",
                                "Authorization" to null,
                            ),
                        )
                    },
                    models = listOf(model()),
                    api = api,
                ),
            ),
        )

        val events = registry.stream(
            model(),
            Context(messages = emptyList()),
            SimpleStreamOptions(
                env = mapOf("B" to "explicit-b", "C" to "explicit-c"),
                headers = mapOf("CF-AIG-AUTHORIZATION" to "Bearer explicit-override"),
            ),
        ).toList()

        assertTrue(events.single() is AssistantMessageEvent.Done)
        // Resolver supplies the key; env merges per field with the request on top.
        assertEquals("resolved-key", api.lastApiKey)
        assertEquals(mapOf("A" to "resolved-a", "B" to "explicit-b", "C" to "explicit-c"), api.lastEnv)
        // Explicit request headers win over resolved auth headers case-insensitively.
        assertEquals(
            mapOf(
                "Authorization" to null,
                "CF-AIG-AUTHORIZATION" to "Bearer explicit-override",
            ),
            api.lastHeaders,
        )
    }
}
