package works.resolve.pathfinder.codingagent.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.providers.ProviderCatalog

/** Ports of pi's model-resolver.test.ts resolveModelReference cases. */
class ModelReferenceResolutionTest {

    private fun referenceModel(provider: String, id: String): Model = Model(
        id = id,
        name = id,
        api = "openai-completions",
        provider = provider,
        baseUrl = "https://example.invalid",
        reasoning = true
    )

    private val referenceCatalog = listOf(
        referenceModel("anthropic", "claude-sonnet-4-5"),
        referenceModel("anthropic", "claude-sonnet-4-5-20250929"),
        referenceModel("anthropic", "claude-opus-4-8"),
        referenceModel("anthropic", "exact"),
        // Fuzzy-matches "shared"; an ambiguous exact "shared" must not fall through to it.
        referenceModel("anthropic", "shared-pro"),
        referenceModel("openai", "gpt-4o"),
        referenceModel("openrouter", "qwen/qwen3-coder:exacto"),
        referenceModel("openrouter", "openai/gpt-4o:extended"),
        referenceModel("gw-a", "shared"),
        referenceModel("gw-a", "anthropic/future"),
        referenceModel("gw-a", "anthropic/exact"),
        referenceModel("gw-a", "anthropic/claude-opus-4.8"),
        referenceModel("gw-a", "anthropic/claude-opus"),
        referenceModel("gw-b", "shared"),
        referenceModel("gw-b", "anthropic/future"),
        referenceModel("gw-b", "claude-opus-4-8"),
        referenceModel("gw-b", "gw-b/free"),
        referenceModel("gw-b", "zz:free")
    )

    private sealed interface Expected {
        data class Resolved(
            val model: String,
            val thinkingLevel: ModelThinkingLevel? = null,
            val warning: String? = null
        ) : Expected

        data class Ambiguous(val candidates: List<String>, val usableCount: Int) : Expected

        data object NotFound : Expected
    }

    private data class ReferenceCase(
        val name: String,
        val text: String,
        val expected: Expected,
        val usable: Set<String> = emptySet(),
        val provider: String? = null,
        val thinkingSuffix: ThinkingSuffixMode = ThinkingSuffixMode.STRICT,
        val fuzzy: Boolean = true
    )

    private fun modelRef(model: Model): String = "${model.provider}/${model.id}"

    private val referenceCases = listOf(
        // Reference syntax
        ReferenceCase(
            name = "exact id",
            text = "claude-sonnet-4-5",
            expected = Expected.Resolved("anthropic/claude-sonnet-4-5")
        ),
        ReferenceCase(
            name = "exact id is case-insensitive",
            text = "GPT-4O",
            expected = Expected.Resolved("openai/gpt-4o")
        ),
        ReferenceCase(
            name = "fuzzy prefers aliases over dated ids",
            text = "sonnet",
            expected = Expected.Resolved("anthropic/claude-sonnet-4-5")
        ),
        ReferenceCase(
            name = "thinking suffix",
            text = "sonnet:high",
            expected = Expected.Resolved(
                "anthropic/claude-sonnet-4-5",
                thinkingLevel = ModelThinkingLevel.HIGH
            )
        ),
        ReferenceCase(
            name = "colon in a literal id is not a thinking suffix",
            text = "qwen/qwen3-coder:exacto",
            expected = Expected.Resolved("openrouter/qwen/qwen3-coder:exacto")
        ),
        ReferenceCase(
            name = "provider prefix, colon id, and thinking suffix",
            text = "openrouter/qwen/qwen3-coder:exacto:high",
            expected = Expected.Resolved(
                "openrouter/qwen/qwen3-coder:exacto",
                thinkingLevel = ModelThinkingLevel.HIGH
            )
        ),
        ReferenceCase(
            name = "raw id wins when the provider prefix names no matching model",
            text = "openai/gpt-4o:extended",
            expected = Expected.Resolved("openrouter/openai/gpt-4o:extended")
        ),
        ReferenceCase(
            name = "fuzzy searches the prefixed provider first",
            text = "openrouter/qwen",
            expected = Expected.Resolved("openrouter/qwen/qwen3-coder:exacto")
        ),
        ReferenceCase(
            name = "strict keeps an invalid suffix in the id",
            text = "gpt-4o:random",
            expected = Expected.NotFound
        ),
        ReferenceCase(
            name = "lenient drops an invalid suffix with a warning",
            text = "gpt-4o:random",
            thinkingSuffix = ThinkingSuffixMode.LENIENT,
            expected = Expected.Resolved(
                "openai/gpt-4o",
                warning = "Invalid thinking level \"random\""
            )
        ),
        ReferenceCase(
            name = "lenient invalid outer suffix discards the inner thinking level",
            text = "qwen/qwen3-coder:exacto:high:random",
            thinkingSuffix = ThinkingSuffixMode.LENIENT,
            expected = Expected.Resolved(
                "openrouter/qwen/qwen3-coder:exacto",
                warning = "Invalid thinking level \"random\""
            )
        ),
        ReferenceCase(
            name = "none keeps the suffix in the id",
            text = "gpt-4o:high",
            thinkingSuffix = ThinkingSuffixMode.NONE,
            expected = Expected.NotFound
        ),
        ReferenceCase(
            name = "exact-only skips fuzzy matches",
            text = "sonnet",
            fuzzy = false,
            expected = Expected.NotFound
        ),
        ReferenceCase(name = "empty text", text = "  ", expected = Expected.NotFound),

        // Exact matches across providers
        ReferenceCase(
            name = "sole usable provider wins",
            text = "shared",
            usable = setOf("gw-b"),
            expected = Expected.Resolved("gw-b/shared")
        ),
        ReferenceCase(
            name = "no usable provider is ambiguous instead of falling through to fuzzy",
            text = "shared",
            expected = Expected.Ambiguous(
                candidates = listOf("gw-a/shared", "gw-b/shared"),
                usableCount = 0
            )
        ),
        ReferenceCase(
            name = "several usable providers are ambiguous",
            text = "shared",
            usable = setOf("gw-a", "gw-b"),
            expected = Expected.Ambiguous(
                candidates = listOf("gw-a/shared", "gw-b/shared"),
                usableCount = 2
            )
        ),
        ReferenceCase(
            name = "thinking suffix does not change provider selection",
            text = "shared:high",
            usable = setOf("gw-b"),
            expected = Expected.Resolved(
                "gw-b/shared",
                thinkingLevel = ModelThinkingLevel.HIGH
            )
        ),
        ReferenceCase(
            name = "thinking suffix does not bypass ambiguity",
            text = "shared:high",
            usable = setOf("gw-a", "gw-b"),
            expected = Expected.Ambiguous(
                candidates = listOf("gw-a/shared", "gw-b/shared"),
                usableCount = 2
            )
        ),
        ReferenceCase(
            name = "provider prefix without a direct model selects the usable raw id",
            text = "anthropic/future",
            usable = setOf("gw-b"),
            expected = Expected.Resolved("gw-b/anthropic/future")
        ),
        ReferenceCase(
            name = "usable qualified match beats a usable raw id",
            text = "anthropic/exact",
            usable = setOf("anthropic", "gw-a"),
            expected = Expected.Resolved("anthropic/exact")
        ),
        ReferenceCase(
            name = "usable raw id beats an unusable qualified match",
            text = "anthropic/exact",
            usable = setOf("gw-a"),
            expected = Expected.Resolved("gw-a/anthropic/exact")
        ),
        ReferenceCase(
            name = "unusable qualified match is kept when nothing is usable",
            text = "anthropic/exact",
            expected = Expected.Resolved("anthropic/exact")
        ),
        ReferenceCase(
            name = "named provider's usable fuzzy match beats a raw id elsewhere",
            text = "anthropic/claude-opus",
            usable = setOf("anthropic"),
            expected = Expected.Resolved("anthropic/claude-opus-4-8")
        ),
        ReferenceCase(
            name = "usable raw id beats the named provider's unusable fuzzy match",
            text = "anthropic/claude-opus",
            usable = setOf("gw-a"),
            expected = Expected.Resolved("gw-a/anthropic/claude-opus")
        ),
        ReferenceCase(
            name = "named provider's fuzzy match is kept when nothing is usable",
            text = "anthropic/claude-opus",
            expected = Expected.Resolved("anthropic/claude-opus-4-8")
        ),
        ReferenceCase(
            name = "named provider's literal id beats its fuzzy matches",
            text = "gw-b/free",
            expected = Expected.Resolved("gw-b/gw-b/free")
        ),

        // Fuzzy matches across providers
        ReferenceCase(
            name = "fuzzy prefers usable providers",
            text = "opus",
            usable = setOf("gw-a"),
            expected = Expected.Resolved("gw-a/anthropic/claude-opus-4.8")
        ),
        ReferenceCase(
            name = "fuzzy id tie across usable providers is ambiguous",
            text = "opus",
            usable = setOf("anthropic", "gw-a", "gw-b"),
            expected = Expected.Ambiguous(
                candidates = listOf(
                    "anthropic/claude-opus-4-8",
                    "gw-b/claude-opus-4-8"
                ),
                usableCount = 2
            )
        ),
        ReferenceCase(
            name = "fuzzy id tie resolves to the sole usable provider",
            text = "opus",
            usable = setOf("anthropic"),
            expected = Expected.Resolved("anthropic/claude-opus-4-8")
        ),

        // Explicit provider constraint
        ReferenceCase(
            name = "explicit provider ignores usable providers elsewhere",
            text = "shared",
            provider = "gw-a",
            usable = setOf("gw-b"),
            expected = Expected.Resolved("gw-a/shared")
        ),
        ReferenceCase(
            name = "explicit provider accepts its own prefix",
            text = "GW-B/shared",
            provider = "gw-b",
            expected = Expected.Resolved("gw-b/shared")
        ),
        ReferenceCase(
            name = "explicit provider allows fuzzy matches",
            text = "4o",
            provider = "openai",
            expected = Expected.Resolved("openai/gpt-4o")
        ),
        ReferenceCase(
            name = "explicit provider never leaves the provider",
            text = "gpt-4o",
            provider = "gw-a",
            expected = Expected.NotFound
        )
    )

    @Test
    fun `resolves references without catalog order deciding the provider`() {
        for (case in referenceCases) {
            // Catalog order must never decide the result.
            for (catalog in listOf(referenceCatalog, referenceCatalog.reversed())) {
                val result = resolveModelReference(
                    ModelReferenceRequest(
                        text = case.text,
                        provider = case.provider,
                        thinkingSuffix = case.thinkingSuffix,
                        fuzzy = case.fuzzy
                    ),
                    catalog
                ) { it in case.usable }

                when (val expected = case.expected) {
                    is Expected.NotFound -> assertTrue(
                        "${case.name}: expected not-found, got $result",
                        result is ModelReferenceResult.NotFound
                    )

                    is Expected.Ambiguous -> {
                        assertTrue(
                            "${case.name}: expected ambiguous, got $result",
                            result is ModelReferenceResult.Ambiguous
                        )
                        result as ModelReferenceResult.Ambiguous
                        assertEquals(
                            case.name,
                            expected.candidates.sorted(),
                            result.candidates.map(::modelRef).sorted()
                        )
                        assertEquals(case.name, expected.usableCount, result.usableCount)
                    }

                    is Expected.Resolved -> {
                        assertTrue(
                            "${case.name}: expected resolved, got $result",
                            result is ModelReferenceResult.Resolved
                        )
                        result as ModelReferenceResult.Resolved
                        assertEquals(case.name, expected.model, modelRef(result.model))
                        assertEquals(case.name, expected.thinkingLevel, result.thinkingLevel)
                        if (expected.warning != null) {
                            assertTrue(
                                "${case.name}: expected warning containing ${expected.warning}",
                                result.warning?.contains(expected.warning) == true
                            )
                        } else {
                            assertEquals(case.name, null, result.warning)
                        }
                    }
                }
            }
        }
    }
}

/** Ports of pi's model-resolver.test.ts default model selection cases. */
class DefaultModelSelectionTest {

    private val defaults = DEFAULT_MODEL_PER_PROVIDER.toMap()

    @Test
    fun `openai defaults track current models`() {
        assertEquals("gpt-5.5", defaults["openai"])
        assertEquals("gpt-6.1-sol", defaults["openai-codex"])
    }

    @Test
    fun `provider defaults track current catalog models`() {
        assertEquals("gpt-5.4", defaults["azure"])
        assertEquals("gpt-6.1-sol", defaults["github-copilot"])
        assertEquals("nvidia/nemotron-3.5-super-vl-120b-a12b", defaults["nvidia"])
        assertEquals("accounts/fireworks/models/kimi-k3", defaults["fireworks"])
        assertEquals("moonshotai/Kimi-K3", defaults["together"])
        assertEquals("kimi-k3", defaults["opencode-go"])
        assertEquals("mimo-v2.6-pro", defaults["xiaomi"])
        assertEquals("mimo-v2.6-pro", defaults["xiaomi-token-plan-cn"])
        assertEquals("mimo-v2.6-pro", defaults["xiaomi-token-plan-ams"])
        assertEquals("mimo-v2.6-pro", defaults["xiaomi-token-plan-sgp"])
        assertEquals("grok-4.7", defaults["xai"])
    }

    @Test
    fun `every provider default exists in its generated catalog`() {
        val catalog = ProviderCatalog.parse(
            File("../ai/src/main/assets/models-catalog.json").readText()
        )

        for (provider in catalog.providers) {
            val defaultId = defaults[provider.id]
            assertNotNull("${provider.id} has no default model", defaultId)
            assertTrue(
                "${provider.id} default $defaultId should exist in its generated catalog",
                provider.model(defaultId!!) != null
            )
        }
    }
}
