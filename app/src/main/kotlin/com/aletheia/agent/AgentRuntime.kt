package com.aletheia.agent

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

/** Thin owner of one pi runtime. Commands and events remain JSON at the boundary. */
class AgentRuntime(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Closeable {

    private val script = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    private val quickJs = QuickJs.create(dispatcher)
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = EVENT_BUFFER_SIZE)

    val events: SharedFlow<String> = mutableEvents.asSharedFlow()

    suspend fun start() {
        quickJs.function("aletheiaEmit") { args ->
            val event = args.firstOrNull() as? String
            if (event != null) check(mutableEvents.tryEmit(event)) { "Agent event buffer is full" }
        }
        quickJs.evaluate<Unit>(script, filename = ASSET_NAME)
    }

    /** Resolves after pi has completed handling the command. */
    suspend fun command(commandJson: String): String =
        quickJs.evaluate<String>(
            "await globalThis.aletheiaCommand(${JSONObject.quote(commandJson)})",
            filename = "command.js",
        )

    override fun close() {
        quickJs.close()
    }

    private companion object {
        const val ASSET_NAME = "agent.js"
        const val EVENT_BUFFER_SIZE = 64
    }
}
