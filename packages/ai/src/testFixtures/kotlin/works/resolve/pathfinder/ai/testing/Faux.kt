package works.resolve.pathfinder.ai.testing

import kotlin.math.ceil
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.CacheRetention
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.GrammarFormat
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ProviderResponse
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage

fun interface FauxResponseFactory {
    suspend fun create(
        context: Context,
        options: SimpleStreamOptions,
        state: FauxProviderState,
        model: Model
    ): AssistantMessage
}

data class FauxProviderState(var callCount: Int = 0)

class FauxProvider(
    val model: Model = Model(
        id = "faux-1",
        name = "Faux Model",
        api = "faux",
        provider = "faux",
        baseUrl = "http://localhost:0",
        input = listOf(InputModality.TEXT, InputModality.IMAGE),
        contextWindow = 128_000,
        maxTokens = 16_384
    ),
    additionalModels: List<Model> = emptyList(),
    configuredAuth: Boolean = true
) {
    private sealed interface ResponseStep {
        data class Fixed(val message: AssistantMessage) : ResponseStep
        data class Factory(val factory: FauxResponseFactory) : ResponseStep
    }

    private val responses = ArrayDeque<ResponseStep>()
    private val promptCache = mutableMapOf<String, String>()
    val state = FauxProviderState()

    private val api = object : ChatApi {
        override fun streamSimple(
            model: Model,
            context: Context,
            options: SimpleStreamOptions
        ): Flow<AssistantMessageEvent> {
            val step = responses.removeFirstOrNull()
            state.callCount++

            return flow {
                try {
                    options.onResponse?.invoke(ProviderResponse(200, emptyMap()), model)
                    if (step == null) {
                        throw IllegalStateException("No more faux responses queued")
                    }
                    val response = when (step) {
                        is ResponseStep.Fixed -> step.message

                        is ResponseStep.Factory -> step.factory.create(
                            context,
                            options,
                            state,
                            model
                        )
                    }
                    val message = response.copy(
                        api = model.api,
                        provider = model.provider,
                        model = model.id
                    ).withUsageEstimate(context, options, promptCache)
                    streamWithDeltas(message).collect { emit(it) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = AssistantMessage(
                        content = emptyList(),
                        api = model.api,
                        provider = model.provider,
                        model = model.id,
                        stopReason = StopReason.ERROR,
                        errorMessage = error.message ?: "Unknown error"
                    ).withUsageEstimate(context, options, promptCache)
                    emit(AssistantMessageEvent.Error(StopReason.ERROR, message))
                }
            }
        }
    }

    val models = Models(
        listOf(
            Provider(
                id = model.provider,
                name = model.provider,
                baseUrl = model.baseUrl,
                authResolver = { _, _ ->
                    if (configuredAuth) ResolvedAuth(apiKey = "faux-key") else null
                },
                models = listOf(model) + additionalModels,
                apis = mapOf(model.api to api)
            )
        )
    )

    fun setResponses(vararg messages: AssistantMessage) {
        responses.clear()
        responses.addAll(messages.map(ResponseStep::Fixed))
    }

    fun appendResponse(message: AssistantMessage) {
        responses.add(ResponseStep.Fixed(message))
    }

    fun appendResponse(factory: FauxResponseFactory) {
        responses.add(ResponseStep.Factory(factory))
    }
}

private fun streamWithDeltas(message: AssistantMessage): Flow<AssistantMessageEvent> = flow {
    val content = mutableListOf<Content>()
    fun partial() = message.copy(content = content.toList(), stopReason = StopReason.PENDING)

    emit(AssistantMessageEvent.Start(partial()))
    for ((index, block) in message.content.withIndex()) {
        when (block) {
            is ThinkingContent -> {
                content.add(ThinkingContent(""))
                emit(AssistantMessageEvent.ThinkingStart(index, partial()))
                for (chunk in splitStringByTokenSize(block.thinking)) {
                    val current = content[index] as ThinkingContent
                    content[index] = current.copy(thinking = current.thinking + chunk)
                    emit(AssistantMessageEvent.ThinkingDelta(index, chunk, partial()))
                }
                emit(AssistantMessageEvent.ThinkingEnd(index, block.thinking, partial()))
            }

            is TextContent -> {
                content.add(TextContent(""))
                emit(AssistantMessageEvent.TextStart(index, partial()))
                for (chunk in splitStringByTokenSize(block.text)) {
                    val current = content[index] as TextContent
                    content[index] = current.copy(text = current.text + chunk)
                    emit(AssistantMessageEvent.TextDelta(index, chunk, partial()))
                }
                emit(AssistantMessageEvent.TextEnd(index, block.text, partial()))
            }

            is ToolCall -> {
                content.add(block.copy(arguments = "{}"))
                emit(AssistantMessageEvent.ToolCallStart(index, partial()))
                for (chunk in splitStringByTokenSize(block.arguments)) {
                    emit(AssistantMessageEvent.ToolCallDelta(index, chunk, partial()))
                }
                content[index] = block
                emit(AssistantMessageEvent.ToolCallEnd(index, block, partial()))
            }

            else -> Unit
        }
    }

    if (message.stopReason == StopReason.PENDING) {
        throw IllegalStateException("Faux response ended without a stop reason")
    }
    if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
        emit(AssistantMessageEvent.Error(message.stopReason, message))
    } else {
        emit(AssistantMessageEvent.Done(message.stopReason, message))
    }
}

private fun splitStringByTokenSize(text: String): List<String> {
    val chunks = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        val charSize = Random.nextInt(3, 6) * 4
        chunks.add(text.substring(index, minOf(index + charSize, text.length)))
        index += charSize
    }
    return chunks.ifEmpty { listOf("") }
}

private fun estimateTokens(text: String): Int = ceil(text.length / 4.0).toInt()

private fun contentToText(content: List<Content>): String = content.joinToString("\n") {
    when (it) {
        is TextContent -> it.text
        is ImageContent -> "[image:${it.mimeType}:${it.data.length}]"
        else -> ""
    }
}

private fun assistantContentToText(content: List<Content>): String = content.joinToString("\n") {
    when (it) {
        is TextContent -> it.text
        is ThinkingContent -> it.thinking
        is ToolCall -> "${it.name}:${it.arguments}"
        else -> ""
    }
}

private fun toolResultToText(message: ToolResultMessage): String =
    (listOf(message.toolName) + message.content.map { contentToText(listOf(it)) })
        .joinToString("\n")

private fun messageToText(message: Message): String = when (message) {
    is UserMessage -> contentToText(message.content)
    is AssistantMessage -> assistantContentToText(message.content)
    is ToolResultMessage -> toolResultToText(message)
}

private fun messageRole(message: Message): String = when (message) {
    is UserMessage -> "user"
    is AssistantMessage -> "assistant"
    is ToolResultMessage -> "toolResult"
}

private fun serializeTools(tools: List<Tool>): JsonArray = JsonArray(tools.map(::toolToJson))

private fun toolToJson(tool: Tool): JsonObject = buildJsonObject {
    put("name", tool.name)
    put("description", tool.description)
    put("parameters", tool.parameters)
    tool.constrainedSampling?.let { put("constrainedSampling", it.toJson()) }
}

private fun ConstrainedSamplingConfig.toJson(): JsonElement = when (this) {
    ConstrainedSamplingConfig.Disabled -> JsonPrimitive(false)

    is ConstrainedSamplingConfig.JsonSchema -> buildJsonObject {
        put("type", "json_schema")
        put("strict", strict.name.lowercase())
    }

    is ConstrainedSamplingConfig.Grammar -> buildJsonObject {
        put("type", "grammar")
        put(
            "variants",
            buildJsonObject {
                variants.forEach { (format, definition) ->
                    put(
                        if (format == GrammarFormat.OPENAI_LARK) {
                            "openai_lark"
                        } else {
                            "openai_regex"
                        },
                        definition
                    )
                }
            }
        )
    }
}

private fun serializeContext(context: Context): String {
    val parts = mutableListOf<String>()
    if (!context.systemPrompt.isNullOrEmpty()) {
        parts.add("system:${context.systemPrompt}")
    }
    for (message in context.messages) {
        parts.add("${messageRole(message)}:${messageToText(message)}")
    }
    if (context.tools.isNotEmpty()) {
        parts.add("tools:${serializeTools(context.tools)}")
    }
    return parts.joinToString("\n\n")
}

private fun commonPrefixLength(a: String, b: String): Int {
    val length = minOf(a.length, b.length)
    var index = 0
    while (index < length && a[index] == b[index]) index++
    return index
}

private fun AssistantMessage.withUsageEstimate(
    context: Context,
    options: SimpleStreamOptions,
    promptCache: MutableMap<String, String>
): AssistantMessage {
    val promptText = serializeContext(context)
    val promptTokens = estimateTokens(promptText)
    val outputTokens = estimateTokens(assistantContentToText(content))
    var input = promptTokens
    var cacheRead = 0
    var cacheWrite = 0
    val sessionId = options.sessionId

    if (sessionId != null && options.cacheRetention != CacheRetention.NONE) {
        val previousPrompt = promptCache[sessionId]
        if (previousPrompt != null) {
            val cachedChars = commonPrefixLength(previousPrompt, promptText)
            cacheRead = estimateTokens(previousPrompt.substring(0, cachedChars))
            cacheWrite = estimateTokens(promptText.substring(cachedChars))
            input = maxOf(0, promptTokens - cacheRead)
        } else {
            cacheWrite = promptTokens
        }
        promptCache[sessionId] = promptText
    }

    return copy(
        usage = Usage(
            input = input,
            output = outputTokens,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            totalTokens = input + outputTokens + cacheRead + cacheWrite
        )
    )
}
