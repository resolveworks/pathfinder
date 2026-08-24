package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.SimpleStreamOptions
import works.resolve.aletheia.ai.core.StopReason
import kotlinx.coroutines.flow.Flow

/**
 * A chat API implementation (the pi "Api" concept). Implementations stream
 * assistant message events and encode failures in the stream itself rather
 * than throwing. The dispatch entry is [streamSimple] (pi's streamSimple
 * ownership in Models): provider-neutral options, translated per API.
 */
interface ChatApi {
    fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): Flow<AssistantMessageEvent>
}

/** Thrown internally by streaming implementations; surfaced as an error event. */
class ProviderStreamException(
    message: String,
    val stopReason: StopReason = StopReason.ERROR,
) : Exception(message)
