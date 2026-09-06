package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.ai.modelThinkingLevelFromWire

/** pi's defaults.ts DEFAULT_THINKING_LEVEL. */
internal val DEFAULT_THINKING_LEVEL = ModelThinkingLevel.MEDIUM

/**
 * pi's `defaultModelPerProvider` (model-resolver.ts), restricted to the
 * generated catalog's providers — every preferred id exists in the catalog.
 * Consulted in table order when no default is set: the first table-listed
 * provider with that model available wins over the options' own order.
 */
internal val DEFAULT_MODEL_PER_PROVIDER: List<Pair<String, String>> = listOf(
    "ant-ling" to "Ring-2.6-1T",
    "anthropic" to "claude-opus-4-8",
    "openai" to "gpt-5.5",
    "azure-openai-responses" to "gpt-5.4",
    "openai-codex" to "gpt-5.5",
    "nvidia" to "nvidia/nemotron-3-super-120b-a12b",
    "deepseek" to "deepseek-v4-pro",
    "google" to "gemini-3.1-pro-preview",
    "github-copilot" to "gpt-5.4",
    "openrouter" to "moonshotai/kimi-k2.6",
    "vercel-ai-gateway" to "zai/glm-5.1",
    "xai" to "grok-4.6",
    "groq" to "openai/gpt-oss-120b",
    "cerebras" to "gpt-oss-120b",
    "zai" to "glm-5.3",
    "zai-coding-cn" to "glm-5.3",
    "mistral" to "devstral-medium-latest",
    "minimax" to "MiniMax-M2.7",
    "minimax-cn" to "MiniMax-M2.7",
    "moonshotai" to "kimi-k2.6",
    "moonshotai-cn" to "kimi-k2.6",
    "huggingface" to "moonshotai/Kimi-K2.6",
    "fireworks" to "accounts/fireworks/models/kimi-k2p6",
    "together" to "moonshotai/Kimi-K2.6",
    "baseten" to "zai-org/GLM-5.2",
    "opencode" to "kimi-k2.6",
    "opencode-go" to "kimi-k2.6",
    "kimi-coding" to "kimi-for-coding",
    "cloudflare-workers-ai" to "@cf/moonshotai/kimi-k2.6",
    "cloudflare-ai-gateway" to "workers-ai/@cf/moonshotai/kimi-k2.6",
    "qwen-token-plan" to "qwen3.7-max",
    "qwen-token-plan-cn" to "qwen3.7-max",
    "qwen-token-plan-individual" to "qwen3.8-max",
    "xiaomi" to "mimo-v2.5-pro",
    "xiaomi-token-plan-cn" to "mimo-v2.5-pro",
    "xiaomi-token-plan-ams" to "mimo-v2.5-pro",
    "xiaomi-token-plan-sgp" to "mimo-v2.5-pro"
)

/**
 * pi's ModelRuntime.getAvailableSnapshot analog: the models of providers
 * with configured auth. Divergence: restricted to APIs with a ported
 * implementation, since the agent cannot stream the rest.
 */
internal suspend fun Models.getAvailableSnapshot(): List<Model> = getProviders()
    .filter { checkAuth(it.id) }
    .flatMap { provider -> provider.models.filter { ChatApiRegistry.isSupported(it.api) } }

data class InitialModelResult(val model: Model?, val thinkingLevel: ModelThinkingLevel)

/**
 * pi's findInitialModel (model-resolver.ts), minus the CLI step: the first
 * scoped model (skipped when continuing, where the session's own fold wins
 * in [createAgentSession]), else the saved default while it resolves and is
 * authenticated, else the per-provider preferred model in table order, else
 * the first available model.
 */
internal suspend fun findInitialModel(
    scopedModels: List<ScopedModel>,
    isContinuing: Boolean,
    defaultProvider: String?,
    defaultModelId: String?,
    defaultThinkingLevel: ModelThinkingLevel?,
    modelThinkingLevels: Map<String, ModelThinkingLevel>,
    models: Models
): InitialModelResult {
    if (scopedModels.isNotEmpty() && !isContinuing) {
        val scoped = scopedModels.first()
        val perModel =
            modelThinkingLevels["${scoped.model.provider}/${scoped.model.id}"]
        return InitialModelResult(
            model = scoped.model,
            thinkingLevel = scoped.thinkingLevel ?: perModel ?: defaultThinkingLevel
                ?: DEFAULT_THINKING_LEVEL
        )
    }

    if (defaultProvider != null && defaultModelId != null) {
        val found = models.getModel(defaultProvider, defaultModelId)
        if (found != null && models.checkAuth(found.provider)) {
            val perModel = modelThinkingLevels["$defaultProvider/$defaultModelId"]
            return InitialModelResult(
                model = found,
                thinkingLevel = perModel ?: defaultThinkingLevel ?: DEFAULT_THINKING_LEVEL
            )
        }
    }

    val availableModels = models.getAvailableSnapshot()
    if (availableModels.isNotEmpty()) {
        for ((providerId, modelId) in DEFAULT_MODEL_PER_PROVIDER) {
            val match = availableModels.firstOrNull {
                it.provider == providerId && it.id == modelId
            }
            if (match != null) {
                return InitialModelResult(match, DEFAULT_THINKING_LEVEL)
            }
        }
        return InitialModelResult(availableModels.first(), DEFAULT_THINKING_LEVEL)
    }

    return InitialModelResult(model = null, thinkingLevel = DEFAULT_THINKING_LEVEL)
}

data class ResolveModelScopeResult(
    val scopedModels: List<ScopedModel>,
    val diagnostics: List<String>
)

/**
 * pi's resolveModelScopeFromModels (model-resolver.ts): resolves each
 * enabledModels pattern to concrete models — exact reference, else glob
 * (matched case-insensitively against "provider/modelId" and the bare id),
 * else the best partial match (alias preferred over dated versions, latest
 * within each) — with an optional ":level" thinking suffix. Divergence: the
 * glob translation covers `*` and `?` only (no character classes, extglob,
 * or braces); stored patterns are plain references.
 */
internal fun resolveModelScope(
    patterns: List<String>,
    availableModels: List<Model>
): ResolveModelScopeResult {
    val scopedModels = mutableListOf<ScopedModel>()
    val diagnostics = mutableListOf<String>()

    for (pattern in patterns) {
        if (pattern.any { it == '*' || it == '?' }) {
            val (globPattern, thinkingLevel) = splitThinkingSuffix(pattern)
            val exactMatch =
                findExactModelReferenceMatch(globPattern, availableModels)
            if (exactMatch != null) {
                addScoped(scopedModels, exactMatch, thinkingLevel)
                continue
            }
            val regex = globToRegex(globPattern)
            val matching = availableModels.filter { model ->
                val fullId = "${model.provider}/${model.id}"
                regex.matches(fullId) || regex.matches(model.id)
            }
            if (matching.isEmpty()) {
                diagnostics.add("No models match pattern \"$pattern\"")
                continue
            }
            for (model in matching) {
                addScoped(scopedModels, model, thinkingLevel)
            }
            continue
        }

        val (model, thinkingLevel, warning) = parseModelPattern(pattern, availableModels)
        if (warning != null) {
            diagnostics.add(warning)
        }
        if (model == null) {
            diagnostics.add("No models match pattern \"$pattern\"")
            continue
        }
        addScoped(scopedModels, model, thinkingLevel)
    }

    return ResolveModelScopeResult(scopedModels.toList(), diagnostics.toList())
}

private fun addScoped(
    scopedModels: MutableList<ScopedModel>,
    model: Model,
    thinkingLevel: ModelThinkingLevel?
) {
    if (scopedModels.none { Models.modelsAreEqual(it.model, model) }) {
        scopedModels.add(ScopedModel(model, thinkingLevel))
    }
}

private fun globToRegex(pattern: String): Regex {
    val sb = StringBuilder()
    for (c in pattern) {
        when (c) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            else -> sb.append(Regex.escape(c.toString()))
        }
    }
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}

/**
 * pi's findExactModelReferenceMatch: a bare model id or a canonical
 * "provider/modelId" reference; ambiguous bare-id matches across providers
 * are rejected.
 */
internal fun findExactModelReferenceMatch(
    modelReference: String,
    availableModels: List<Model>
): Model? {
    val trimmed = modelReference.trim()
    if (trimmed.isEmpty()) return null
    val normalized = trimmed.lowercase()

    val canonicalMatches = availableModels.filter {
        "${it.provider}/${it.id}".lowercase() == normalized
    }
    if (canonicalMatches.size == 1) return canonicalMatches.first()
    if (canonicalMatches.size > 1) return null

    val slashIndex = trimmed.indexOf('/')
    if (slashIndex != -1) {
        val provider = trimmed.substring(0, slashIndex).trim()
        val modelId = trimmed.substring(slashIndex + 1).trim()
        if (provider.isNotEmpty() && modelId.isNotEmpty()) {
            val providerMatches = availableModels.filter {
                it.provider.lowercase() == provider.lowercase() &&
                    it.id.lowercase() == modelId.lowercase()
            }
            if (providerMatches.size == 1) return providerMatches.first()
            if (providerMatches.size > 1) return null
        }
    }

    val idMatches = availableModels.filter { it.id.lowercase() == normalized }
    return if (idMatches.size == 1) idMatches.first() else null
}

/** pi's isAlias: an id without a date suffix (-YYYYMMDD) or ending in -latest. */
private fun isAlias(id: String): Boolean {
    if (id.endsWith("-latest")) return true
    return !Regex("-\\d{8}$").containsMatchIn(id)
}

/** pi's tryMatchModel: exact reference, else best partial match by id or name. */
private fun tryMatchModel(modelPattern: String, availableModels: List<Model>): Model? {
    findExactModelReferenceMatch(modelPattern, availableModels)?.let { return it }

    val matches = availableModels.filter {
        it.id.lowercase().contains(modelPattern.lowercase()) ||
            it.name?.lowercase()?.contains(modelPattern.lowercase()) == true
    }
    if (matches.isEmpty()) return null

    val aliases = matches.filter { isAlias(it.id) }
    val pickFrom = if (aliases.isNotEmpty()) aliases else matches
    return pickFrom.maxByOrNull { it.id }
}

private data class ParsedModelPattern(
    val model: Model?,
    val thinkingLevel: ModelThinkingLevel?,
    val warning: String?
)

private fun splitThinkingSuffix(pattern: String): Pair<String, ModelThinkingLevel?> {
    val lastColon = pattern.lastIndexOf(':')
    if (lastColon == -1) return pattern to null
    val suffix = pattern.substring(lastColon + 1)
    val level = modelThinkingLevelFromWire(suffix) ?: return pattern to null
    return pattern.substring(0, lastColon) to level
}

/**
 * pi's parseModelPattern: match the full pattern, else split off a trailing
 * ":level" (valid → used, invalid → warned and dropped) and recurse on the
 * prefix — model ids may themselves contain colons.
 */
private fun parseModelPattern(pattern: String, availableModels: List<Model>): ParsedModelPattern {
    val exactMatch = tryMatchModel(pattern, availableModels)
    if (exactMatch != null) {
        return ParsedModelPattern(exactMatch, null, null)
    }

    val lastColon = pattern.lastIndexOf(':')
    if (lastColon == -1) {
        return ParsedModelPattern(null, null, null)
    }
    val prefix = pattern.substring(0, lastColon)
    val suffix = pattern.substring(lastColon + 1)
    val level = modelThinkingLevelFromWire(suffix)

    val inner = parseModelPattern(prefix, availableModels)
    if (level != null) {
        return ParsedModelPattern(
            inner.model,
            if (inner.warning == null && inner.model != null) level else null,
            inner.warning
        )
    }
    if (inner.model != null) {
        return ParsedModelPattern(
            inner.model,
            null,
            "Invalid thinking level \"$suffix\" in pattern \"$pattern\". Using default instead."
        )
    }
    return inner
}
