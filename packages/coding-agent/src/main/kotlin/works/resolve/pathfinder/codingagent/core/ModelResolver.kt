package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.ai.modelThinkingLevelFromWire
import works.resolve.pathfinder.ai.utils.trimJsWhitespace

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
    "azure" to "gpt-5.4",
    "openai-codex" to "gpt-6.1-sol",
    "nvidia" to "nvidia/nemotron-3.5-super-vl-120b-a12b",
    "deepseek" to "deepseek-v4-pro",
    "google" to "gemini-3.1-pro-preview",
    "github-copilot" to "gpt-6.1-sol",
    "openrouter" to "moonshotai/kimi-k2.6",
    "vercel-ai-gateway" to "zai/glm-5.1",
    "xai" to "grok-4.7",
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
    "fireworks" to "accounts/fireworks/models/kimi-k3",
    "together" to "moonshotai/Kimi-K3",
    "baseten" to "zai-org/GLM-5.2",
    "opencode" to "kimi-k2.6",
    "opencode-go" to "kimi-k3",
    "kimi-coding" to "kimi-for-coding",
    "cloudflare-workers-ai" to "@cf/moonshotai/kimi-k2.6",
    "cloudflare-ai-gateway" to "workers-ai/@cf/moonshotai/kimi-k2.6",
    "qwen-token-plan" to "qwen3.7-max",
    "qwen-token-plan-cn" to "qwen3.7-max",
    "qwen-token-plan-individual" to "qwen3.8-max",
    "xiaomi" to "mimo-v2.6-pro",
    "xiaomi-token-plan-cn" to "mimo-v2.6-pro",
    "xiaomi-token-plan-ams" to "mimo-v2.6-pro",
    "xiaomi-token-plan-sgp" to "mimo-v2.6-pro"
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

/*
 * Model reference resolution. Candidates are collected before a provider is chosen, so catalog order
 * never decides the provider and an ambiguous match is reported instead of falling through to a
 * weaker one.
 */

internal enum class ThinkingSuffixMode {
    /** The suffix stays part of the id. */
    NONE,

    /** A valid thinking level is parsed; any other suffix stays part of the id. */
    STRICT,

    /** Like [STRICT], but an invalid suffix is dropped with a warning. */
    LENIENT
}

internal data class ModelReferenceRequest(
    /** Bare model id or `provider/id`, optionally followed by `:<thinking>`. */
    val text: String,
    /** Only consider models from this provider. */
    val provider: String? = null,
    /**
     * A trailing `:<suffix>` is only considered when the full text matches no model, since ids such as
     * OpenRouter's `qwen/qwen3-coder:exacto` contain colons.
     */
    val thinkingSuffix: ThinkingSuffixMode,
    /** Match id and name substrings when no exact match exists. */
    val fuzzy: Boolean
)

internal data class ModelReferenceTarget(
    val provider: String,
    /** The provider's models, anchored at the first entry; never empty. */
    val providerModels: List<Model>,
    /** Text after the provider prefix as typed, including any `:<suffix>`. */
    val modelText: String,
    /** `modelText` without a valid thinking suffix (unless `thinkingSuffix` is `none`). */
    val modelId: String,
    val thinkingLevel: ModelThinkingLevel?
)

internal sealed interface ModelReferenceResult {
    data class Resolved(
        val model: Model,
        val thinkingLevel: ModelThinkingLevel? = null,
        val warning: String? = null
    ) : ModelReferenceResult

    data class Ambiguous(
        val candidates: List<Model>,
        val usableCount: Int,
        val thinkingLevel: ModelThinkingLevel? = null,
        val warning: String? = null
    ) : ModelReferenceResult

    data class NotFound(
        /**
         * Set when a provider is explicit or named by a `provider/` prefix, for building a custom
         * model id.
         */
        val target: ModelReferenceTarget? = null
    ) : ModelReferenceResult
}

private val ModelReferenceResult.warning: String?
    get() = when (this) {
        is ModelReferenceResult.Resolved -> warning
        is ModelReferenceResult.Ambiguous -> warning
        is ModelReferenceResult.NotFound -> null
    }

private data class ThinkingSuffix(val base: String, val thinkingLevel: ModelThinkingLevel)

private fun splitThinkingSuffix(text: String): ThinkingSuffix? {
    val colonIndex = text.lastIndexOf(':')
    if (colonIndex == -1) return null
    val suffix = text.substring(colonIndex + 1)
    val thinkingLevel = modelThinkingLevelFromWire(suffix) ?: return null
    return ThinkingSuffix(text.substring(0, colonIndex), thinkingLevel)
}

private data class ProviderPrefix(val provider: String, val modelText: String)

private fun splitProviderPrefix(text: String, models: List<Model>): ProviderPrefix? {
    val slashIndex = text.indexOf('/')
    if (slashIndex == -1) return null
    val prefix = trimJsWhitespace(text.substring(0, slashIndex)).lowercase()
    val modelText = trimJsWhitespace(text.substring(slashIndex + 1))
    if (prefix.isEmpty() || modelText.isEmpty()) return null
    val provider = models.firstOrNull { it.provider.lowercase() == prefix }?.provider
    return provider?.let { ProviderPrefix(it, modelText) }
}

/**
 * `usable` only breaks ties between providers, so a unique match resolves even without credentials
 * (`--api-key` first-time setup).
 */
internal fun resolveModelReference(
    request: ModelReferenceRequest,
    models: List<Model>,
    usable: (String) -> Boolean
): ModelReferenceResult {
    val text = trimJsWhitespace(request.text)
    if (text.isEmpty()) return ModelReferenceResult.NotFound()
    val constraint = request.provider?.lowercase()
    val pool =
        if (constraint != null) {
            models.filter { it.provider.lowercase() == constraint }
        } else {
            models
        }
    val result = resolveText(text, request, pool, usable)
    if (result !is ModelReferenceResult.NotFound) return result

    val split = splitProviderPrefix(text, pool)
    val anchor =
        if (constraint != null) {
            pool.firstOrNull()
        } else {
            pool.firstOrNull { it.provider == split?.provider }
        }
    if (anchor == null) return result
    val modelText = split?.modelText ?: text
    val suffix =
        if (request.thinkingSuffix == ThinkingSuffixMode.NONE) {
            null
        } else {
            splitThinkingSuffix(modelText)
        }
    return ModelReferenceResult.NotFound(
        ModelReferenceTarget(
            provider = anchor.provider,
            providerModels = listOf(anchor) + pool.filter {
                it.provider == anchor.provider && it !== anchor
            },
            modelText = modelText,
            modelId = suffix?.base ?: modelText,
            thinkingLevel = suffix?.thinkingLevel
        )
    )
}

/** Exact `provider/id` or bare id lookup without thinking suffixes or fuzzy matching. */
internal fun findExactModelReferenceMatch(modelReference: String, models: List<Model>): Model? {
    val result = resolveModelReference(
        ModelReferenceRequest(
            text = modelReference,
            thinkingSuffix = ThinkingSuffixMode.NONE,
            fuzzy = false
        ),
        models
    ) { true }
    return (result as? ModelReferenceResult.Resolved)?.model
}

private fun resolveText(
    text: String,
    request: ModelReferenceRequest,
    pool: List<Model>,
    usable: (String) -> Boolean
): ModelReferenceResult {
    selectReference(text, pool, request.fuzzy, usable)?.let { return it }

    if (request.fuzzy) {
        selectFuzzy(fuzzyMatches(text, pool), usable)?.let { return it }
    }

    if (request.thinkingSuffix == ThinkingSuffixMode.NONE) {
        return ModelReferenceResult.NotFound()
    }
    val thinking = splitThinkingSuffix(text)
    if (thinking != null) {
        val inner = resolveText(thinking.base, request, pool, usable)
        if (inner is ModelReferenceResult.NotFound) return inner
        // Only use this thinking level if no warning from inner recursion
        return inner.withThinkingLevel(
            if (inner.warning != null) null else thinking.thinkingLevel
        )
    }
    // Keep an invalid suffix as part of the id and fail, rather than resolve a different model
    if (request.thinkingSuffix == ThinkingSuffixMode.STRICT) {
        return ModelReferenceResult.NotFound()
    }
    val colonIndex = text.lastIndexOf(':')
    if (colonIndex == -1) return ModelReferenceResult.NotFound()

    val suffix = text.substring(colonIndex + 1)
    val inner = resolveText(text.substring(0, colonIndex), request, pool, usable)
    if (inner is ModelReferenceResult.NotFound) return inner
    return inner
        .withThinkingLevel(null)
        .withWarning(
            "Invalid thinking level \"$suffix\" in pattern \"$text\". Using default instead."
        )
}

/**
 * `provider/rest` is only a hint: the whole text may also be a model id on another provider, such as
 * gateway id `zai/glm-5` or commandcode id `xiaomi/mimo-v2.6-pro`. The named provider wins when usable,
 * since that is what the syntax usually means; otherwise a sole usable raw match wins, since the named
 * provider could not serve the request. An unusable named match is still preferred over unusable raw
 * matches, so `--api-key` can set up the named provider.
 */
private fun selectReference(
    text: String,
    pool: List<Model>,
    fuzzy: Boolean,
    usable: (String) -> Boolean
): ModelReferenceResult? {
    val lowerText = text.lowercase()
    val raw = pool.filter { it.id.lowercase() == lowerText }
    val split = splitProviderPrefix(text, pool)
    var named: Model? = null
    if (split != null) {
        val providerModels = pool.filter { it.provider == split.provider }
        val lowerModelText = split.modelText.lowercase()
        // The provider's own literal id (OpenRouter's `openrouter/free`) beats its fuzzy matches
        named = providerModels.firstOrNull { it.id.lowercase() == lowerModelText }
            ?: raw.firstOrNull { it.provider == split.provider }
            ?: if (fuzzy) {
                bestFuzzyMatch(fuzzyMatches(split.modelText, providerModels))
            } else {
                null
            }
    }

    val others = raw.filter { it !== named }
    val candidates = if (named != null) listOf(named) + others else others
    if (candidates.isEmpty()) return null
    if (candidates.map { it.provider }.toSet().size == 1) {
        return ModelReferenceResult.Resolved(candidates.first())
    }
    if (named != null && usable(named.provider)) {
        return ModelReferenceResult.Resolved(named)
    }
    val usableOthers = others.filter { usable(it.provider) }
    if (usableOthers.size == 1) {
        return ModelReferenceResult.Resolved(usableOthers.first())
    }
    if (usableOthers.isEmpty() && named != null) {
        return ModelReferenceResult.Resolved(named)
    }
    return ModelReferenceResult.Ambiguous(candidates, usableOthers.size)
}

private fun fuzzyMatches(text: String, models: List<Model>): List<Model> {
    val lowerText = text.lowercase()
    return models.filter {
        it.id.lowercase().contains(lowerText) || it.name.lowercase().contains(lowerText)
    }
}

private fun selectFuzzy(matches: List<Model>, usable: (String) -> Boolean): ModelReferenceResult? {
    if (matches.isEmpty()) return null
    val multipleProviders = matches.map { it.provider }.toSet().size > 1
    val usableMatches =
        if (multipleProviders) matches.filter { usable(it.provider) } else emptyList()
    val pool = if (usableMatches.isNotEmpty()) usableMatches else matches
    val best = bestFuzzyMatch(pool) ?: return null
    // The same id on several providers would otherwise be decided by catalog order
    val tied = pool.filter { it.id == best.id }
    if (tied.map { it.provider }.toSet().size == 1) {
        return ModelReferenceResult.Resolved(best)
    }
    return ModelReferenceResult.Ambiguous(
        candidates = tied,
        usableCount = if (usableMatches.isNotEmpty()) tied.size else 0
    )
}

/**
 * Prefer aliases (`claude-sonnet-4-5`) over dated versions
 * (`claude-sonnet-4-5-20250929`), then the latest.
 */
private fun bestFuzzyMatch(matches: List<Model>): Model? {
    val aliases = matches.filter { isAlias(it.id) }
    return (if (aliases.isNotEmpty()) aliases else matches).maxByOrNull { it.id }
}

private fun isAlias(id: String): Boolean =
    id.endsWith("-latest") || !Regex("-\\d{8}$").containsMatchIn(id)

private fun ModelReferenceResult.withThinkingLevel(
    level: ModelThinkingLevel?
): ModelReferenceResult = when (this) {
    is ModelReferenceResult.Resolved -> copy(thinkingLevel = level)
    is ModelReferenceResult.Ambiguous -> copy(thinkingLevel = level)
    is ModelReferenceResult.NotFound -> this
}

private fun ModelReferenceResult.withWarning(warning: String): ModelReferenceResult = when (this) {
    is ModelReferenceResult.Resolved -> copy(warning = warning)
    is ModelReferenceResult.Ambiguous -> copy(warning = warning)
    is ModelReferenceResult.NotFound -> this
}

enum class ModelScopeDiagnosticCode(val wire: String) {
    NO_MATCH("no-match"),
    INVALID_THINKING_LEVEL("invalid-thinking-level"),
    AMBIGUOUS("ambiguous")
}

data class ModelScopeDiagnostic(
    val type: String = "warning",
    val code: ModelScopeDiagnosticCode,
    val message: String,
    val pattern: String
)

data class ResolveModelScopeResult(
    val scopedModels: List<ScopedModel>,
    val diagnostics: List<ModelScopeDiagnostic>
)

/**
 * pi's resolveModelScopeFromModels (model-resolver.ts): resolves each
 * enabledModels pattern to concrete models — exact reference, else glob
 * (matched case-insensitively against "provider/modelId" and the bare id),
 * else the best partial match (alias preferred over dated versions, latest
 * within each) — with an optional ":level" thinking suffix. Divergence: the
 * glob translation covers `*` and `?` only, with minimatch's slash-free
 * segment semantics (no character classes, extglob, braces, or `**`
 * globstar); stored patterns are plain references.
 *
 * Every model is usable here, so credentials cannot choose between providers
 * offering the same id. All of them are added, with a warning so the caller
 * can pick one with `provider/id`.
 *
 * Public for the app layer, which resolves the stored patterns against its
 * own available-model snapshot to update a live session's scoped models
 * (pi's interactive-mode updateSessionModels).
 */
fun resolveModelScope(
    patterns: List<String>,
    availableModels: List<Model>
): ResolveModelScopeResult {
    val scopedModels = mutableListOf<ScopedModel>()
    val diagnostics = mutableListOf<ModelScopeDiagnostic>()
    fun add(model: Model, thinkingLevel: ModelThinkingLevel?) {
        if (scopedModels.none { Models.modelsAreEqual(it.model, model) }) {
            scopedModels.add(ScopedModel(model, thinkingLevel))
        }
    }

    fun noMatch(pattern: String) {
        diagnostics.add(
            ModelScopeDiagnostic(
                code = ModelScopeDiagnosticCode.NO_MATCH,
                message = "No models match pattern \"$pattern\"",
                pattern = pattern
            )
        )
    }

    fun ambiguous(pattern: String, candidates: List<Model>) {
        val providers = candidates.map { it.provider }.toSet().sorted()
        diagnostics.add(
            ModelScopeDiagnostic(
                code = ModelScopeDiagnosticCode.AMBIGUOUS,
                message = "Pattern \"$pattern\" matches several providers: " +
                    "${providers.joinToString(", ")}. Use provider/id to choose one.",
                pattern = pattern
            )
        )
    }

    for (pattern in patterns) {
        // Check if pattern contains glob characters
        if (pattern.any { it == '*' || it == '?' || it == '[' }) {
            // Extract optional thinking level suffix (e.g., "provider/*:high")
            val thinking = splitThinkingSuffix(pattern)
            val globPattern = thinking?.base ?: pattern
            val thinkingLevel = thinking?.thinkingLevel

            // Ids such as `bracketed-model[1m]` are not globs
            val exact = resolveModelReference(
                ModelReferenceRequest(
                    text = globPattern,
                    thinkingSuffix = ThinkingSuffixMode.NONE,
                    fuzzy = false
                ),
                availableModels
            ) { true }
            val matches = when (exact) {
                is ModelReferenceResult.Resolved -> listOf(exact.model)

                is ModelReferenceResult.Ambiguous -> {
                    ambiguous(pattern, exact.candidates)
                    exact.candidates
                }

                is ModelReferenceResult.NotFound -> {
                    // Match against "provider/modelId" format OR just model ID
                    // This allows "*sonnet*" to match without requiring "anthropic/*sonnet*"
                    val regex = globToRegex(globPattern)
                    availableModels.filter { model ->
                        regex.matches("${model.provider}/${model.id}") ||
                            regex.matches(model.id)
                    }
                }
            }
            if (matches.isEmpty()) noMatch(pattern)
            for (model in matches) add(model, thinkingLevel)
            continue
        }

        val result = resolveModelReference(
            ModelReferenceRequest(
                text = pattern,
                thinkingSuffix = ThinkingSuffixMode.LENIENT,
                fuzzy = true
            ),
            availableModels
        ) { true }
        if (result is ModelReferenceResult.NotFound) {
            noMatch(pattern)
            continue
        }
        if (result.warning != null) {
            diagnostics.add(
                ModelScopeDiagnostic(
                    code = ModelScopeDiagnosticCode.INVALID_THINKING_LEVEL,
                    message = result.warning!!,
                    pattern = pattern
                )
            )
        }
        if (result is ModelReferenceResult.Ambiguous) {
            ambiguous(pattern, result.candidates)
        }
        val matches = when (result) {
            is ModelReferenceResult.Resolved -> listOf(result.model)
            is ModelReferenceResult.Ambiguous -> result.candidates
            is ModelReferenceResult.NotFound -> error("unreachable")
        }
        for (model in matches) add(model, result.thinkingLevel)
    }

    return ResolveModelScopeResult(scopedModels.toList(), diagnostics.toList())
}

private val ModelReferenceResult.thinkingLevel: ModelThinkingLevel?
    get() = when (this) {
        is ModelReferenceResult.Resolved -> thinkingLevel
        is ModelReferenceResult.Ambiguous -> thinkingLevel
        is ModelReferenceResult.NotFound -> null
    }

private fun globToRegex(pattern: String): Regex {
    val sb = StringBuilder()
    for (c in pattern) {
        when (c) {
            '*' -> sb.append("[^/]*")
            '?' -> sb.append("[^/]")
            else -> sb.append(Regex.escape(c.toString()))
        }
    }
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}
