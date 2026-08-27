package works.resolve.pathfinder.ai.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * OpenRouter structured `reasoning_details` support, ported from pi's
 * packages/ai/src/api/openai-completions.ts:
 *
 * - `isOpenAIReasoningDetail` (openai-completions.ts:144)
 * - `parseOpenAIReasoningDetails` (openai-completions.ts:225)
 * - `parseLegacyEncryptedReasoningDetail` (openai-completions.ts:236)
 * - `fillMissingCommonReasoningDetailFields` (openai-completions.ts:246)
 * - `appendOpenAIReasoningDetail` (openai-completions.ts:259)
 *
 * A reasoning detail is one of `reasoning.summary`, `reasoning.encrypted`, or
 * `reasoning.text`; OpenRouter streams them as deltas that consecutive
 * text/summary entries merge into, while encrypted entries stay opaque and
 * discrete. Accumulated details are serialized as a JSON array into the
 * thinking block's `thinkingSignature` slot, and replayed as
 * `assistantMsg.reasoning_details` on the next request.
 */

/** pi's isReasoningDetailObject (openai-completions.ts:128): a JSON object. */
private fun isReasoningDetailObject(detail: JsonElement): Boolean =
    detail is JsonObject

/** pi's hasValidCommonReasoningDetailFields (openai-completions.ts:134):
 * id is string|null|absent, format is string|absent, index is number|absent. */
private fun hasValidCommonReasoningDetailFields(detail: JsonObject): Boolean {
    val id = detail["id"]
    if (id != null && id !is JsonNull && !id.isStringPrimitive()) return false
    val format = detail["format"]
    if (format != null && format !is JsonNull && !format.isStringPrimitive()) return false
    val index = detail["index"]
    if (index != null && index !is JsonNull &&
        !((index as? JsonPrimitive)?.let { it.longOrNull != null || it.doubleOrNull != null } == true)
    ) {
        return false
    }
    return true
}

private fun JsonElement.isStringPrimitive(): Boolean =
    this is JsonPrimitive && this.isString

/** Reads a plain string field, or null when absent/not a string. */
private fun Map<String, JsonElement>.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/** pi's isOpenAIReasoningDetail (openai-completions.ts:144). */
internal fun isOpenAiReasoningDetail(detail: JsonElement): Boolean {
    if (!isReasoningDetailObject(detail) || !hasValidCommonReasoningDetailFields(detail as JsonObject)) {
        return false
    }
    return when (detail.stringField("type")) {
        "reasoning.summary" -> detail.stringField("summary") != null
        "reasoning.encrypted" -> detail.stringField("data") != null
        "reasoning.text" ->
            detail.stringField("text") != null &&
                (detail["signature"] == null || detail["signature"] is JsonNull || detail.stringField("signature") != null)
        else -> false
    }
}

/**
 * pi's parseOpenAIReasoningDetails (openai-completions.ts:225): parses a
 * thinkingSignature as a non-empty array of valid reasoning details.
 */
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

/**
 * pi's parseLegacyEncryptedReasoningDetail (openai-completions.ts:236): an
 * encrypted detail with a non-empty id and data, stored on a tool call's
 * `thoughtSignature` by older stored assistant messages.
 */
internal fun parseLegacyEncryptedReasoningDetail(signature: String?): JsonObject? {
    if (signature == null) return null
    val parsed = try {
        kotlinx.serialization.json.Json.parseToJsonElement(signature)
    } catch (_: Exception) {
        return null
    }
    if (parsed !is JsonObject || !isOpenAiReasoningDetail(parsed)) return null
    if (parsed.stringField("type") != "reasoning.encrypted") return null
    val id = parsed.stringField("id") ?: return null
    val data = parsed.stringField("data") ?: return null
    return if (id.isNotEmpty() && data.isNotEmpty()) parsed else null
}

/** JS `??=`: set only when the target value is absent or null and source has one. */
private fun fillMissing(target: MutableMap<String, JsonElement>, key: String, source: Map<String, JsonElement>) {
    val current = target[key]
    if ((target.containsKey(key) && current !is JsonNull)) return
    val value = source[key]
    if (value != null && value !is JsonNull) target[key] = value
}

/** pi's fillMissingCommonReasoningDetailFields (openai-completions.ts:246). */
private fun fillMissingCommonReasoningDetailFields(
    target: MutableMap<String, JsonElement>,
    source: Map<String, JsonElement>,
) {
    fillMissing(target, "id", source)
    // `||=`: also replaces an empty string.
    if (target.stringField("format") == null || target.stringField("format")!!.isEmpty()) {
        source.stringField("format")?.let { target["format"] = JsonPrimitive(it) }
    }
    fillMissing(target, "index", source)
}

/**
 * pi's appendOpenAIReasoningDetail (openai-completions.ts:259): merges
 * consecutive text/summary deltas into the last entry; everything else
 * (including encrypted entries) is appended as a discrete copy.
 */
internal fun appendOpenAIReasoningDetail(
    details: MutableList<MutableMap<String, JsonElement>>,
    detail: Map<String, JsonElement>,
) {
    val lastDetail = details.lastOrNull()
    if (detail.stringField("type") == "reasoning.text" && lastDetail?.stringField("type") == "reasoning.text") {
        lastDetail["text"] = JsonPrimitive(lastDetail.stringField("text")!! + detail.stringField("text")!!)
        if (lastDetail.stringField("signature") == null || lastDetail.stringField("signature")!!.isEmpty()) {
            detail.stringField("signature")?.let { lastDetail["signature"] = JsonPrimitive(it) }
        }
        fillMissingCommonReasoningDetailFields(lastDetail, detail)
        return
    }
    if (detail.stringField("type") == "reasoning.summary" && lastDetail?.stringField("type") == "reasoning.summary") {
        lastDetail["summary"] =
            JsonPrimitive(lastDetail.stringField("summary")!! + detail.stringField("summary")!!)
        fillMissingCommonReasoningDetailFields(lastDetail, detail)
        return
    }
    details.add(LinkedHashMap(detail))
}
