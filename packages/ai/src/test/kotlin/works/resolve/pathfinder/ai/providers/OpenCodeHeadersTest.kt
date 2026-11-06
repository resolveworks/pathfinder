package works.resolve.pathfinder.ai.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.fixedApiKeyAuth

class OpenCodeHeadersTest {

    private class RecordingApi : ChatApi {
        var captured: SimpleStreamOptions? = null

        override fun streamSimple(
            model: Model,
            context: works.resolve.pathfinder.ai.TranscriptContext,
            options: SimpleStreamOptions
        ): Flow<AssistantMessageEvent> {
            captured = options
            return emptyFlow()
        }
    }

    private val model = Model(
        id = "model",
        name = "Model",
        api = "opencode",
        provider = "custom",
        baseUrl = "https://opencode.ai/zen/v1",
        input = listOf(InputModality.TEXT)
    )

    private fun models(api: RecordingApi) = Models(
        listOf(
            Provider(
                id = "custom",
                name = "Custom",
                baseUrl = model.baseUrl,
                auth = fixedApiKeyAuth("key"),
                models = listOf(model),
                apis = mapOf(model.api to api)
            )
        )
    )

    private suspend fun stream(api: RecordingApi, options: SimpleStreamOptions) {
        models(api).stream(
            model,
            Context(messages = listOf(UserMessage.ofText("hi"))),
            options
        ).toList()
    }

    @Test
    fun `adds the session header when unset`() = runTest {
        val api = RecordingApi()
        stream(api, SimpleStreamOptions(sessionId = "session-1"))
        assertEquals("session-1", api.captured?.headers?.get("x-opencode-session"))
    }

    @Test
    fun `a null session header still suppresses adding it`() = runTest {
        // Explicit request headers win over the target-derived session header.
        val api = RecordingApi()
        stream(
            api,
            SimpleStreamOptions(
                sessionId = "session-1",
                headers = mapOf("x-opencode-session" to null)
            )
        )
        assertNull(api.captured?.headers?.get("x-opencode-session"))
        assertTrue(api.captured?.headers?.containsKey("x-opencode-session") == true)
    }

    @Test
    fun `suppression is case insensitive and keeps the caller value`() = runTest {
        val api = RecordingApi()
        stream(
            api,
            SimpleStreamOptions(
                sessionId = "session-1",
                headers = mapOf("X-Opencode-Session" to "caller-value")
            )
        )
        assertEquals("caller-value", api.captured?.headers?.get("X-Opencode-Session"))
        assertFalse(api.captured?.headers?.containsKey("x-opencode-session") == true)
    }

    @Test
    fun `no session id leaves the options untouched`() = runTest {
        val api = RecordingApi()
        stream(api, SimpleStreamOptions())
        assertTrue(api.captured?.headers?.isEmpty() == true)
    }
}
