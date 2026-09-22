package works.resolve.pathfinder.ai.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.utils.normalizeContext

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
        provider = "opencode",
        baseUrl = "https://example.test",
        input = listOf(InputModality.TEXT)
    )

    private val context = normalizeContext(Context(messages = listOf(UserMessage.ofText("hi"))))

    @Test
    fun `adds the session header when unset`() = runTest {
        val api = RecordingApi()
        OpenCodeSessionHeaderChatApi(api).streamSimple(
            model,
            context,
            SimpleStreamOptions(sessionId = "session-1")
        )
        assertEquals("session-1", api.captured?.headers?.get(OPENCODE_SESSION_HEADER))
    }

    @Test
    fun `a null session header still suppresses adding it`() = runTest {
        // pi's suppression checks header presence, not a non-blank value: an
        // explicit null keeps the header absent downstream.
        val api = RecordingApi()
        OpenCodeSessionHeaderChatApi(api).streamSimple(
            model,
            context,
            SimpleStreamOptions(
                sessionId = "session-1",
                headers = mapOf(OPENCODE_SESSION_HEADER to null)
            )
        )
        assertNull(api.captured?.headers?.get(OPENCODE_SESSION_HEADER))
        assertTrue(api.captured?.headers?.containsKey(OPENCODE_SESSION_HEADER) == true)
    }

    @Test
    fun `suppression is case insensitive and keeps the caller value`() = runTest {
        val api = RecordingApi()
        OpenCodeSessionHeaderChatApi(api).streamSimple(
            model,
            context,
            SimpleStreamOptions(
                sessionId = "session-1",
                headers = mapOf("X-Opencode-Session" to "caller-value")
            )
        )
        assertEquals("caller-value", api.captured?.headers?.get("X-Opencode-Session"))
        assertFalse(api.captured?.headers?.containsKey(OPENCODE_SESSION_HEADER) == true)
    }

    @Test
    fun `no session id leaves the options untouched`() = runTest {
        val api = RecordingApi()
        OpenCodeSessionHeaderChatApi(api).streamSimple(
            model,
            context,
            SimpleStreamOptions()
        )
        assertTrue(api.captured?.headers?.isEmpty() == true)
    }
}
