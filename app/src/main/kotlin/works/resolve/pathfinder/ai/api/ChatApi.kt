package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import kotlinx.coroutines.flow.Flow

/**
 * Implementations stream assistant message events and encode failures in
 * the stream itself rather than throwing.
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

class ProviderAuthException(message: String) : Exception(message)

/**
 * Control-flow sentinel thrown to unwind SSE collection once a terminal
 * chunk has been processed (upstream streams simply end; the DOM-based
 * collectors need an explicit unwind). Shared — adapters must not redeclare
 * private copies.
 */
internal class DoneSentinel : RuntimeException()
