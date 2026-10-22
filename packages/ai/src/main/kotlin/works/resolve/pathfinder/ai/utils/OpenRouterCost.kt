package works.resolve.pathfinder.ai.utils

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.ai.Usage

fun isOpenRouterModel(model: ClientIdentityTarget): Boolean =
    model.provider == "openrouter" || model.baseUrl.contains("openrouter.ai")

private fun JsonElement?.reportedCostOrNull(): Double? =
    strictDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

/**
 * Replaces the catalog total with OpenRouter's billed cost when the response
 * contains complete, valid accounting. Non-BYOK requests use the charged
 * `cost`; BYOK requests add the cost billed through the user's upstream
 * provider account to it.
 */
fun applyOpenRouterReportedCost(usage: Usage, reported: JsonObject): Usage {
    val reportedCost = reported["cost"].reportedCostOrNull() ?: return usage

    return when (reported.strictBoolean("is_byok")) {
        false -> usage.copy(
            cost = usage.cost.copy(total = reportedCost, totalFromProvider = true)
        )

        true -> {
            val details = reported.obj("cost_details") ?: return usage
            val upstreamElement = details["upstream_inference_cost"]
            val upstreamCost = if (upstreamElement is JsonNull) {
                val prompt = details["upstream_inference_prompt_cost"].reportedCostOrNull()
                    ?: return usage
                val completions = details["upstream_inference_completions_cost"]
                    .reportedCostOrNull() ?: return usage
                prompt + completions
            } else {
                upstreamElement.reportedCostOrNull() ?: return usage
            }
            usage.copy(
                cost = usage.cost.copy(
                    total = reportedCost + upstreamCost,
                    totalFromProvider = true
                )
            )
        }

        else -> usage
    }
}
