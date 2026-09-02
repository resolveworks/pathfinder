package works.resolve.pathfinder.ai.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * OpenRouter structured `reasoning_details`: one of `reasoning.summary`,
 * `reasoning.encrypted`, or `reasoning.text`, streamed as deltas that
 * consecutive text/summary entries merge into while encrypted entries stay
 * opaque and discrete. Accumulated details are serialized as a JSON array into
 * the thinking block's `thinkingSignature` slot and replayed as
 * `assistantMsg.reasoning_details` on the next request.
 */

private fun isReasoningDetailObject(detail: JsonElement): Boolean =
    detail is JsonObject

private fun hasValidCommonReasoningDetailFields(detail: JsonObject): Boolean {
    val id = detail["id"]
    if (id != null && id !is JsonNull && id.stringOrNull() == null) return false
    val format = detail["format"]
    if (format != null && format !is JsonNull && format.stringOrNull() == null) return false
    val index = detail["index"]
    if (index != null && index !is JsonNull &&
        // pi guards with `typeof index === "number"`; numeric primitives only.
        (index as? JsonPrimitive)?.let { it.longOrNull != null || it.doubleOrNull != null } != true
    ) {
        return false
    }
    return true
}

internal fun isOpenAiReasoningDetail(detail: JsonElement): Boolean {
    if (!isReasoningDetailObject(detail) || !hasValidCommonReasoningDetailFields(detail as JsonObject)) {
        return false
    }
    return when (detail.string("type")) {
        "reasoning.summary" -> detail.string("summary") != null
        "reasoning.encrypted" -> detail.string("data") != null
        "reasoning.text" ->
            detail.string("text") != null &&
                (detail["signature"] == null || detail["signature"] is JsonNull || detail.string("signature") != null)
        else -> false
    }
}

internal fun parseOpenAIReasoningDetails(signature: String?): JsonArray? {
    if (signature == null) return null
    val parsed = try {
        kotlinx.serialization.json.Json.parseToJsonElement(signature)
    } catch (_: Exception) {
        return null
    }
    if (parsed !is JsonArray || parsed.size == 0 || parsed.any { !isOpenAiReasoningDetail(it) }) {
        return null
    }
    return parsed
}

/** Legacy format: an encrypted detail stored on a tool call's
 * `thoughtSignature` by older assistant messages. */
internal fun parseLegacyEncryptedReasoningDetail(signature: String?): JsonObject? {
    if (signature == null) return null
    val parsed = try {
        kotlinx.serialization.json.Json.parseToJsonElement(signature)
    } catch (_: Exception) {
        return null
    }
    if (parsed !is JsonObject || !isOpenAiReasoningDetail(parsed)) return null
    if (parsed.string("type") != "reasoning.encrypted") return null
    val id = parsed.string("id") ?: return null
    val data = parsed.string("data") ?: return null
    return if (id.isNotEmpty() && data.isNotEmpty()) parsed else null
}

/** JS `??=` semantics: a present-but-null value counts as missing. */
private fun fillMissing(target: MutableMap<String, JsonElement>, key: String, source: Map<String, JsonElement>) {
    val current = target[key]
    if ((target.containsKey(key) && current !is JsonNull)) return
    val value = source[key]
    if (value != null && value !is JsonNull) target[key] = value
}

private fun fillMissingCommonReasoningDetailFields(
    target: MutableMap<String, JsonElement>,
    source: Map<String, JsonElement>,
) {
    fillMissing(target, "id", source)
    // `||=`: also replaces an empty string.
    val targetView = JsonObject(target)
    val format = targetView.string("format")
    if (format == null || format.isEmpty()) {
        JsonObject(source).string("format")?.let { target["format"] = JsonPrimitive(it) }
    }
    fillMissing(target, "index", source)
}

internal fun appendOpenAIReasoningDetail(
    details: MutableList<MutableMap<String, JsonElement>>,
    detail: Map<String, JsonElement>,
) {
    val lastDetail = details.lastOrNull()
    // Reads go through JsonObject views of the mutable maps (shared strict surface).
    val view = JsonObject(detail)
    val lastView = lastDetail?.let(::JsonObject)
    if (view.string("type") == "reasoning.text" && lastView?.string("type") == "reasoning.text") {
        lastDetail!!["text"] = JsonPrimitive(lastView.string("text")!! + view.string("text")!!)
        if (lastView.string("signature") == null || lastView.string("signature")!!.isEmpty()) {
            view.string("signature")?.let { lastDetail["signature"] = JsonPrimitive(it) }
        }
        fillMissingCommonReasoningDetailFields(lastDetail, detail)
        return
    }
    if (view.string("type") == "reasoning.summary" && lastView?.string("type") == "reasoning.summary") {
        lastDetail!!["summary"] =
            JsonPrimitive(lastView.string("summary")!! + view.string("summary")!!)
        fillMissingCommonReasoningDetailFields(lastDetail, detail)
        return
    }
    details.add(LinkedHashMap(detail))
}
