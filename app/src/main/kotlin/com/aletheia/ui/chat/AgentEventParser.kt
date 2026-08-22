package com.aletheia.ui.chat

import org.json.JSONObject

object AgentEventParser {
    fun parse(eventJson: String): AgentEvent? {
        val json = JSONObject(eventJson)
        return when (json.getString("type")) {
            "initialized" -> AgentEvent.Initialized
            "text_delta" -> AgentEvent.TextDelta(json.getString("delta"))
            "message_end" -> AgentEvent.MessageEnd
            "agent_end" -> AgentEvent.AgentEnd
            "error" -> AgentEvent.Error(json.getString("message"))
            else -> null
        }
    }
}
