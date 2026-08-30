package works.resolve.pathfinder.data.sessions

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessageMetaInfo
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import kotlin.time.Instant

/** Koog [Message] factory helpers shared by session-layer tests. */
fun userMessage(text: String, epochMs: Long = 0L): Message.User =
    Message.User(
        content = text,
        metaInfo = RequestMetaInfo(Instant.fromEpochMilliseconds(epochMs)),
    )

fun assistantMessage(
    vararg parts: MessagePart.ResponsePart,
    epochMs: Long = 0L,
): Message.Assistant =
    Message.Assistant(
        parts = parts.toList(),
        metaInfo = ResponseMetaInfo(Instant.fromEpochMilliseconds(epochMs)),
    )

fun textPart(text: String): MessagePart.Text = MessagePart.Text(text)

fun reasoningPart(vararg content: String): MessagePart.Reasoning = MessagePart.Reasoning(content.toList())

fun toolCallPart(id: String, tool: String, args: String): MessagePart.Tool.Call =
    MessagePart.Tool.Call(id = id, tool = tool, args = args)
