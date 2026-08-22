package com.aletheia.agent

import android.content.Context
import android.os.SystemClock
import com.aletheia.logging.AppLogger
import com.aletheia.logging.LogLevel
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Thin QuickJS host. Commands and callbacks remain explicit JSON/string boundaries. */
class AgentRuntime(
    context: Context,
    private val logger: AppLogger,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AgentClient {

    private val assets = context.applicationContext.assets
    private val quickJs = QuickJs.create(dispatcher)
    private val eventChannel = Channel<AgentEvent>(capacity = Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    private var state = State.New

    override val events: Flow<AgentEvent> = eventChannel.receiveAsFlow()

    override suspend fun start(config: AgentConfig) {
        check(state == State.New) { "Agent runtime has already been started" }
        check(!closed.get()) { "Agent runtime is closed" }
        state = State.Starting
        logger.log(
            LogLevel.Info,
            COMPONENT,
            "start_requested",
            mapOf(
                "providerId" to config.providerId,
                "modelId" to config.modelId,
            ),
        )

        try {
            val script = withContext(ioDispatcher) {
                assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }
            logger.log(
                LogLevel.Debug,
                COMPONENT,
                "asset_loaded",
                mapOf("assetBytes" to script.length.toString()),
            )
            bindHostCallbacks()
            timedCommand("evaluate_bundle") {
                quickJs.evaluate<Unit>(script, filename = ASSET_NAME)
            }
            call("initialize", config.providerId, config.modelId)
            state = State.Ready
            logger.log(LogLevel.Info, COMPONENT, "start_completed")
        } catch (error: CancellationException) {
            state = State.Failed
            logger.log(LogLevel.Info, COMPONENT, "start_cancelled")
            throw error
        } catch (error: Exception) {
            state = State.Failed
            logger.log(LogLevel.Error, COMPONENT, "start_failed", error = error)
            throw error
        }
    }

    override suspend fun prompt(text: String) {
        requireReady()
        call("prompt", text, safeFields = mapOf("textLength" to text.length.toString()))
    }

    override suspend fun abort() {
        requireReady()
        call("abort")
    }

    private suspend fun bindHostCallbacks() {
        quickJs.function(EMIT_FUNCTION) { args ->
            val jsonText = args.firstOrNull() as? String
            if (jsonText == null) {
                logger.log(LogLevel.Error, COMPONENT, "event_callback_invalid")
            } else {
                receiveEvent(jsonText)
            }
        }
        quickJs.function(LOG_FUNCTION) { args ->
            val jsonText = args.firstOrNull() as? String
            if (jsonText == null) {
                logger.log(LogLevel.Warn, COMPONENT, "log_callback_invalid")
            } else {
                receiveRuntimeLog(jsonText)
            }
        }
        logger.log(LogLevel.Debug, COMPONENT, "host_callbacks_bound")
    }

    private fun receiveEvent(jsonText: String) {
        try {
            val event = AgentProtocol.decodeEvent(jsonText)
            val fields = buildMap {
                put("type", event.type)
                put("payloadBytes", jsonText.length.toString())
                if (event is AgentEvent.TextDelta) put("deltaLength", event.delta.length.toString())
            }
            logger.log(LogLevel.Debug, COMPONENT, "event_received", fields)
            if (eventChannel.trySend(event).isFailure) {
                logger.log(LogLevel.Warn, COMPONENT, "event_after_close", mapOf("type" to event.type))
            }
        } catch (error: Exception) {
            logger.log(
                LogLevel.Error,
                COMPONENT,
                "event_decode_failed",
                mapOf("payloadBytes" to jsonText.length.toString()),
                error,
            )
            eventChannel.trySend(AgentEvent.Error("The agent sent an invalid event"))
        }
    }

    private fun receiveRuntimeLog(jsonText: String) {
        try {
            val entry = AgentProtocol.decodeLog(jsonText)
            logger.log(entry.level, JS_COMPONENT, entry.event, entry.fields)
        } catch (error: Exception) {
            logger.log(
                LogLevel.Warn,
                COMPONENT,
                "runtime_log_decode_failed",
                mapOf("payloadBytes" to jsonText.length.toString()),
                error,
            )
        }
    }

    private suspend fun call(
        method: String,
        vararg arguments: String,
        safeFields: Map<String, String> = emptyMap(),
    ) {
        val args = arguments.joinToString(",") { JSONObject.quote(it) }
        timedCommand(method, safeFields) {
            quickJs.evaluate<Unit>(
                "await globalThis.aletheia.$method($args)",
                filename = COMMAND_FILENAME,
            )
        }
    }

    private suspend inline fun timedCommand(
        command: String,
        fields: Map<String, String> = emptyMap(),
        crossinline block: suspend () -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        logger.log(LogLevel.Debug, COMPONENT, "command_started", fields + ("command" to command))
        try {
            block()
            logger.log(
                LogLevel.Debug,
                COMPONENT,
                "command_completed",
                fields + mapOf(
                    "command" to command,
                    "durationMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                ),
            )
        } catch (error: CancellationException) {
            logger.log(
                LogLevel.Info,
                COMPONENT,
                "command_cancelled",
                fields + mapOf(
                    "command" to command,
                    "durationMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                ),
            )
            throw error
        } catch (error: Exception) {
            logger.log(
                LogLevel.Error,
                COMPONENT,
                "command_failed",
                fields + mapOf(
                    "command" to command,
                    "durationMs" to (SystemClock.elapsedRealtime() - startedAt).toString(),
                ),
                error,
            )
            throw error
        }
    }

    private fun requireReady() {
        check(state == State.Ready && !closed.get()) { "Agent runtime is not ready" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        state = State.Closed
        eventChannel.close()
        quickJs.close()
        logger.log(LogLevel.Info, COMPONENT, "closed")
    }

    private enum class State {
        New,
        Starting,
        Ready,
        Failed,
        Closed,
    }

    private companion object {
        const val COMPONENT = "AgentRuntime"
        const val JS_COMPONENT = "AgentJs"
        const val ASSET_NAME = "agent.js"
        const val COMMAND_FILENAME = "aletheia-command.js"
        const val EMIT_FUNCTION = "aletheiaEmit"
        const val LOG_FUNCTION = "aletheiaLog"
    }
}
