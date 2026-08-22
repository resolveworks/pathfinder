package com.aletheia.agent

import com.aletheia.logging.LogLevel
import org.json.JSONObject

internal object AgentProtocol {

    fun decodeEvent(jsonText: String): AgentEvent {
        val json = JSONObject(jsonText)
        return when (val type = json.requiredString("type")) {
            "initialized" -> AgentEvent.Initialized(
                providerId = json.requiredString("providerId"),
                modelId = json.requiredString("modelId"),
            )
            "text_delta" -> AgentEvent.TextDelta(json.requiredString("delta"))
            "message_end" -> AgentEvent.MessageEnd
            "agent_end" -> AgentEvent.AgentEnd
            "error" -> AgentEvent.Error(json.requiredString("message"))
            else -> throw AgentProtocolException("Unknown agent event type '$type'")
        }
    }

    fun decodeLog(jsonText: String): RuntimeLogEntry {
        val json = JSONObject(jsonText)
        val fieldsJson = json.optJSONObject("fields")
        val fields = buildMap {
            fieldsJson?.keys()?.forEach { key ->
                val value = fieldsJson.opt(key)
                if (value != null && value !== JSONObject.NULL) put(key, value.toString())
            }
        }
        return RuntimeLogEntry(
            level = when (json.requiredString("level")) {
                "debug" -> LogLevel.Debug
                "info" -> LogLevel.Info
                "warn" -> LogLevel.Warn
                "error" -> LogLevel.Error
                else -> throw AgentProtocolException("Unknown runtime log level")
            },
            event = json.requiredString("event"),
            fields = fields,
        )
    }

    private fun JSONObject.requiredString(name: String): String {
        if (!has(name) || isNull(name)) throw AgentProtocolException("Missing '$name'")
        return getString(name)
    }
}

internal data class RuntimeLogEntry(
    val level: LogLevel,
    val event: String,
    val fields: Map<String, String>,
)

internal class AgentProtocolException(message: String) : Exception(message)
