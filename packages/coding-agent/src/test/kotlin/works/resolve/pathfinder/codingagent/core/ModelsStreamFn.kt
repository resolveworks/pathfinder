package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Models

/**
 * Test-side adapter mirroring the app's stream wiring: the loop passes a
 * normalized transcript, while [Models.stream] takes a raw [Context] whose
 * prompt/tool fields it folds away (a no-op for an already-normalized
 * transcript).
 */
internal fun modelsStreamFn(models: Models): StreamFn = StreamFn { model, context, options ->
    models.stream(model, Context(messages = context.messages), options)
}
