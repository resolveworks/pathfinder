package works.resolve.pathfinder.agent

import kotlinx.coroutines.flow.Flow
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.SimpleStreamOptions

/**
 * Stream function used by the agent loop. Must not throw for request/model/
 * runtime failures — failures are encoded in the returned flow via a terminal
 * [AssistantMessageEvent.Error]. The returned flow is collected exactly once
 * per assistant turn.
 */
fun interface StreamFn {
    fun stream(model: Model, context: Context, options: SimpleStreamOptions): Flow<AssistantMessageEvent>
}
