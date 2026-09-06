package works.resolve.pathfinder.ai.providers

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FauxProvider

class FauxProviderTest {

    private fun assistant(text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "faux",
        provider = "faux",
        model = "faux-1",
        stopReason = StopReason.STOP,
        timestamp = System.currentTimeMillis()
    )

    @Test
    fun `registers a custom provider and estimates usage`() = runTest {
        val model = FauxProvider().model.copy(provider = "custom-faux")
        val faux = FauxProvider(model)
        faux.setResponses(assistant("hello world"))

        val response = faux.models.completeSimple(
            model,
            Context(messages = listOf(UserMessage.ofText("hello")))
        )

        assertEquals("custom-faux", response.provider)
        assertTrue(response.usage.input > 0)
        assertTrue(response.usage.output > 0)
        assertEquals(response.usage.input + response.usage.output, response.usage.totalTokens)
    }

    @Test
    fun `estimates prompt and output tokens from serialized context`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(assistant("done"))
        val parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("text", buildJsonObject { put("type", "string") })
                }
            )
            put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("text"))))
        }
        val tool = Tool("echo", "Echo back text", parameters)
        val context = Context(
            systemPrompt = "sys",
            messages = listOf(
                UserMessage(
                    listOf(TextContent("hello"), ImageContent("abcd", "image/png")),
                    1L
                ),
                assistant("prior"),
                ToolResultMessage(
                    toolCallId = "tool-1",
                    toolName = "echo",
                    content = listOf(TextContent("tool out")),
                    timestamp = 2L
                )
            ),
            tools = listOf(tool)
        )

        val response = faux.models.completeSimple(faux.model, context)
        val promptText = listOf(
            "system:sys",
            "user:hello\n[image:image/png:4]",
            "assistant:prior",
            "toolResult:echo\ntool out",
            "tools:${JsonArray(
                listOf(
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    }
                )
            )}"
        ).joinToString("\n\n")
        val expectedPromptTokens = kotlin.math.ceil(promptText.length / 4.0).toInt()
        val expectedOutputTokens = kotlin.math.ceil("done".length / 4.0).toInt()

        assertEquals(expectedPromptTokens, response.usage.input)
        assertEquals(expectedOutputTokens, response.usage.output)
        assertEquals(0, response.usage.cacheRead)
        assertEquals(0, response.usage.cacheWrite)
        assertEquals(expectedPromptTokens + expectedOutputTokens, response.usage.totalTokens)
    }

    @Test
    fun `does not share cache across sessions or requests without sessionId`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(assistant("first"), assistant("second"), assistant("third"))
        val messages = mutableListOf<works.resolve.pathfinder.ai.Message>(
            UserMessage.ofText("hello")
        )
        val context = Context(messages = messages)

        val first = faux.models.completeSimple(
            faux.model,
            context,
            SimpleStreamOptions(sessionId = "session-1", cacheRetention = CacheRetention.SHORT)
        )
        assertTrue(first.usage.cacheWrite > 0)
        messages.add(first)
        messages.add(UserMessage.ofText("follow up"))

        val second = faux.models.completeSimple(
            faux.model,
            context,
            SimpleStreamOptions(sessionId = "session-2", cacheRetention = CacheRetention.SHORT)
        )
        assertEquals(0, second.usage.cacheRead)
        assertTrue(second.usage.cacheWrite > 0)

        val third = faux.models.completeSimple(faux.model, context)
        assertEquals(0, third.usage.cacheRead)
        assertEquals(0, third.usage.cacheWrite)
    }

    @Test
    fun `simulates prompt caching per sessionId`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(assistant("first"), assistant("second"))
        val messages = mutableListOf<works.resolve.pathfinder.ai.Message>(
            UserMessage.ofText("hello")
        )
        val context = Context(systemPrompt = "Be concise.", messages = messages)
        val options = SimpleStreamOptions(
            sessionId = "session-1",
            cacheRetention = CacheRetention.SHORT
        )

        val first = faux.models.completeSimple(faux.model, context, options)
        assertEquals(0, first.usage.cacheRead)
        assertTrue(first.usage.cacheWrite > 0)
        messages.add(first)
        messages.add(UserMessage.ofText("follow up"))

        val second = faux.models.completeSimple(faux.model, context, options)
        assertTrue(second.usage.cacheRead > 0)
        assertTrue(second.usage.input + second.usage.cacheRead > second.usage.input)
    }

    @Test
    fun `does not simulate caching when cacheRetention is none`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(assistant("first"), assistant("second"))
        val messages = mutableListOf<works.resolve.pathfinder.ai.Message>(
            UserMessage.ofText("hello")
        )
        val context = Context(messages = messages)
        val options = SimpleStreamOptions(
            sessionId = "session-1",
            cacheRetention = CacheRetention.NONE
        )

        faux.models.completeSimple(faux.model, context, options)
        messages.add(assistant("first"))
        messages.add(UserMessage.ofText("follow up"))
        val second = faux.models.completeSimple(faux.model, context, options)

        assertEquals(0, second.usage.cacheRead)
        assertEquals(0, second.usage.cacheWrite)
    }
}
