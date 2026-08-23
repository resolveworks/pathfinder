package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.OpenAiCompletionsOptions
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.ModelThinkingLevel
import works.resolve.aletheia.ai.core.StopReason
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

/**
 * Convenience for the provider-neutral options, mirroring pi's streamSimple.
 * maxTokens defaults to the model's limit and is clamped against the estimated
 * context so the request always leaves safety room for the answer.
 */
fun ChatApi.streamSimple(
    model: Model,
    context: Context,
    options: SimpleStreamOptions,
): Flow<AssistantMessageEvent> {
    val clamped = options.reasoning?.let {
        works.resolve.aletheia.ai.core.clampThinkingLevel(model, ModelThinkingLevel.valueOf(it.name))
    }
    val effort = if (clamped == ModelThinkingLevel.OFF) null else clamped
    val maxTokens = works.resolve.aletheia.ai.utils.clampMaxTokensToContext(
        model,
        context,
        options.maxTokens ?: model.maxTokens,
    )
    return stream(model, context, options.toStreamOptions(effort).copy(maxTokens = maxTokens))
}
