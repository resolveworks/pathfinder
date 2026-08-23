package com.aletheia.ai.api

import com.aletheia.ai.core.AssistantMessageEvent
import com.aletheia.ai.core.Context
import com.aletheia.ai.core.Model
import com.aletheia.ai.core.OpenAiCompletionsOptions
import com.aletheia.ai.core.SimpleStreamOptions
import com.aletheia.ai.core.ModelThinkingLevel
import com.aletheia.ai.core.StopReason
import kotlinx.coroutines.flow.Flow

/**
 * A chat API implementation (the pi "Api" concept). Implementations stream
 * assistant message events and encode failures in the stream itself rather
 * than throwing.
 */
interface ChatApi {
    fun stream(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions,
    ): Flow<AssistantMessageEvent>
}

/** Thrown internally by streaming implementations; surfaced as an error event. */
class ProviderStreamException(
    message: String,
    val stopReason: StopReason = StopReason.ERROR,
) : Exception(message)

/** Convenience for the provider-neutral options, mirroring pi's streamSimple. */
fun ChatApi.streamSimple(
    model: Model,
    context: Context,
    options: SimpleStreamOptions,
): Flow<AssistantMessageEvent> {
    val clamped = options.reasoning?.let {
        com.aletheia.ai.core.clampThinkingLevel(model, ModelThinkingLevel.valueOf(it.name))
    }
    val effort = if (clamped == ModelThinkingLevel.OFF) null else clamped
    return stream(model, context, options.toStreamOptions(effort))
}
