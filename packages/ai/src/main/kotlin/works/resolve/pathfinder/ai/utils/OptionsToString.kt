package works.resolve.pathfinder.ai.utils

import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.SamplingParams
import works.resolve.pathfinder.ai.clampThinkingLevel

/**
 * Redacted `toString()` support for provider options classes. Field values
 * must never be passed directly — secrets via [redactedSecret], maps as
 * `.keys`, hooks as `hook != null`. A redaction compiler plugin was
 * deliberately rejected: it cannot express keys-only redaction and adds a
 * compiler-plugin dependency.
 */
internal fun redactedSecret(secret: String?): String = if (secret == null) "null" else "<redacted>"

internal fun optionsToString(className: String, vararg fields: Pair<String, Any?>): String =
    className +
        fields.joinToString(prefix = "(", separator = ", ", postfix = ")") { (name, value) ->
            "$name=$value"
        }

/**
 * pi's `resolveSamplingParams`: merges model defaults, the clamped thinking
 * level's overrides, and per-request keys in that order; null when all three
 * are absent.
 */
fun resolveSamplingParams(
    model: Model,
    thinkingLevel: ModelThinkingLevel,
    requestParams: SamplingParams?
): SamplingParams? {
    val effectiveThinkingLevel = clampThinkingLevel(model, thinkingLevel)
    val thinkingLevelParams = model.samplingParamsByThinkingLevel?.get(effectiveThinkingLevel)
    if (model.samplingParams == null && thinkingLevelParams == null && requestParams == null) {
        return null
    }
    return buildMap {
        model.samplingParams?.let(::putAll)
        thinkingLevelParams?.let(::putAll)
        requestParams?.let(::putAll)
    }
}
