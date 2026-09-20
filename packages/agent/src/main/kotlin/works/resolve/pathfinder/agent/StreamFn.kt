package works.resolve.pathfinder.agent

import kotlinx.coroutines.flow.Flow
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.TranscriptContext

/**
 * Stream function used by the agent loop (pi's `Models.streamSimple` shape).
 *
 * The loop passes a normalized transcript: the system prompt and tool
 * declarations are carried by the transcript's system messages, never by a
 * separate `systemPrompt`/`tools` context field.
 *
 * Must not throw for request/model/runtime failures — failures are encoded in
 * the returned flow via a terminal [AssistantMessageEvent.Error]. The returned
 * flow is collected exactly once per assistant turn.
 */
fun interface StreamFn {
    fun stream(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent>
}
