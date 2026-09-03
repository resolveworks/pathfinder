package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.ChatTemplateKwargValue
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.OpenAiCompletionsCompat
import works.resolve.pathfinder.ai.core.ThinkingFormat
import works.resolve.pathfinder.ai.core.ThinkingLevelMap
import works.resolve.pathfinder.ai.core.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiCompletionsThinkingFormatsTest {

    private fun model(
        format: ThinkingFormat,
        map: ThinkingLevelMap? = null,
        supportsReasoningEffort: Boolean = true,
        chatTemplateArgs: Map<String, ChatTemplateKwargValue> = emptyMap(),
    ): Model = Model(
        id = "test-model",
        name = "Test",
        api = "openai-completions",
        provider = "test",
        baseUrl = "https://example.invalid",
        reasoning = true,
        thinkingLevelMap = map,
        compat = OpenAiCompletionsCompat(
            supportsStore = false,
            supportsReasoningEffort = supportsReasoningEffort,
            thinkingFormat = format,
            chatTemplateArgs = chatTemplateArgs,
        ),
    )

    private fun body(
        model: Model,
        effort: ModelThinkingLevel? = null,
    ): JsonObject = OpenAiCompletionsPayload.buildRequestBody(
        model,
        Context(messages = listOf(UserMessage.ofText("hi"))),
        OpenAiCompletionsOptions(apiKey = "k", reasoningEffort = effort),
    )

    @Test
    fun `qwen enables thinking and maps effort`() {
        val b = body(model(ThinkingFormat.QWEN), ModelThinkingLevel.HIGH)
        assertEquals(true, b["enable_thinking"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("high", b["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `qwen disables thinking without effort`() {
        val b = body(model(ThinkingFormat.QWEN))
        assertEquals(false, b["enable_thinking"]!!.jsonPrimitive.booleanOrNull)
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `qwen without effort support sends only the toggle`() {
        val b = body(model(ThinkingFormat.QWEN, supportsReasoningEffort = false), ModelThinkingLevel.HIGH)
        assertEquals(true, b["enable_thinking"]!!.jsonPrimitive.booleanOrNull)
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `deepseek enabled thinking`() {
        val b = body(
            model(ThinkingFormat.DEEPSEEK, map = ThinkingLevelMap.of(ModelThinkingLevel.HIGH to "high")),
            ModelThinkingLevel.HIGH,
        )
        assertEquals("enabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", b["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deepseek disabled when map absent`() {
        val b = body(model(ThinkingFormat.DEEPSEEK))
        assertEquals("disabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `deepseek disabled when off entry is a string`() {
        val b = body(
            model(
                ThinkingFormat.DEEPSEEK,
                map = ThinkingLevelMap.of(ModelThinkingLevel.OFF to "none"),
            ),
        )
        assertEquals("disabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `deepseek skips disabled object when off entry is explicit null`() {
        val b = body(
            model(
                ThinkingFormat.DEEPSEEK,
                map = ThinkingLevelMap.of(ModelThinkingLevel.OFF to null),
            ),
        )
        assertFalse(b.containsKey("thinking"))
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `deepseek without effort support omits reasoning_effort`() {
        val b = body(model(ThinkingFormat.DEEPSEEK, supportsReasoningEffort = false), ModelThinkingLevel.HIGH)
        assertEquals("enabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `openrouter sends mapped effort`() {
        val b = body(
            model(
                ThinkingFormat.OPENROUTER,
                map = ThinkingLevelMap.of(ModelThinkingLevel.HIGH to "high"),
            ),
            ModelThinkingLevel.HIGH,
        )
        assertEquals("high", b["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openrouter defaults off effort to none when map absent`() {
        val b = body(model(ThinkingFormat.OPENROUTER))
        assertEquals("none", b["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openrouter uses mapped off value`() {
        val b = body(
            model(
                ThinkingFormat.OPENROUTER,
                map = ThinkingLevelMap.of(ModelThinkingLevel.OFF to "off"),
            ),
        )
        assertEquals("off", b["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openrouter omits reasoning when off entry is explicit null`() {
        val b = body(
            model(
                ThinkingFormat.OPENROUTER,
                map = ThinkingLevelMap.of(ModelThinkingLevel.OFF to null),
            ),
        )
        assertFalse(b.containsKey("reasoning"))
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `together enables reasoning with effort`() {
        val b = body(
            model(
                ThinkingFormat.TOGETHER,
                map = ThinkingLevelMap.of(ModelThinkingLevel.HIGH to "high"),
            ),
            ModelThinkingLevel.HIGH,
        )
        assertEquals(true, b["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("high", b["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `together disables reasoning without effort`() {
        val b = body(model(ThinkingFormat.TOGETHER))
        assertEquals(false, b["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.booleanOrNull)
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `together without effort support omits reasoning_effort`() {
        val b = body(model(ThinkingFormat.TOGETHER, supportsReasoningEffort = false), ModelThinkingLevel.HIGH)
        assertEquals(true, b["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.booleanOrNull)
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `ant-ling sends reasoning only with explicit mapped effort`() {
        val b = body(
            model(
                ThinkingFormat.ANT_LING,
                map = ThinkingLevelMap.of(ModelThinkingLevel.HIGH to "high"),
            ),
            ModelThinkingLevel.HIGH,
        )
        assertEquals("high", b["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ant-ling sends nothing without mapping even when effort set`() {
        // No map: pi's ant-ling branch has no level-name fallback.
        val b = body(model(ThinkingFormat.ANT_LING), ModelThinkingLevel.HIGH)
        assertFalse(b.containsKey("reasoning"))
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `ant-ling sends nothing without effort`() {
        val b = body(
            model(
                ThinkingFormat.ANT_LING,
                map = ThinkingLevelMap.of(ModelThinkingLevel.HIGH to "high"),
            ),
        )
        assertFalse(b.containsKey("reasoning"))
    }

    private fun basetenModel() = model(
        ThinkingFormat.BASETEN,
        map = ThinkingLevelMap.of(
            ModelThinkingLevel.OFF to "none",
            ModelThinkingLevel.HIGH to "high",
        ),
        chatTemplateArgs = mapOf(
            "enable_thinking" to ChatTemplateKwargValue.Ref("thinking.enabled"),
        ),
    )

    @Test
    fun `sends Baseten chat_template_args with reasoning effort`() {
        val b = body(basetenModel(), ModelThinkingLevel.HIGH)
        val args = b["chat_template_args"]!!.jsonObject
        assertEquals(true, args["enable_thinking"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("high", b["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `disables Baseten opt-in reasoning when thinking is off`() {
        val b = body(basetenModel())
        val args = b["chat_template_args"]!!.jsonObject
        assertEquals(false, args["enable_thinking"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("none", b["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `baseten reasoning_effort falls back to level name when map absent`() {
        val m = model(
            ThinkingFormat.BASETEN,
            chatTemplateArgs = mapOf(
                "enable_thinking" to ChatTemplateKwargValue.Ref("thinking.enabled"),
            ),
        )
        val b = body(m, ModelThinkingLevel.HIGH)
        assertEquals("high", b["reasoning_effort"]!!.jsonPrimitive.content)
        // Off with no map: pi leaves reasoning_effort unset (requestedEffort is undefined).
        val bOff = body(m)
        assertFalse(bOff.containsKey("reasoning_effort"))
        assertEquals(false, bOff["chat_template_args"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.booleanOrNull)
    }

    @Test
    fun `baseten omitWhenOff drops the kwarg when thinking is off`() {
        val m = model(
            ThinkingFormat.BASETEN,
            chatTemplateArgs = mapOf(
                "effort" to ChatTemplateKwargValue.Ref("thinking.effort", omitWhenOff = true),
                "enable_thinking" to ChatTemplateKwargValue.Ref("thinking.enabled"),
            ),
        )
        val bOn = body(m, ModelThinkingLevel.HIGH)
        assertEquals("high", bOn["chat_template_args"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val bOff = body(m)
        assertFalse(bOff["chat_template_args"]!!.jsonObject.containsKey("effort"))
    }

    @Test
    fun `baseten scalar kwargs pass through and empty args are omitted`() {
        val m = model(
            ThinkingFormat.BASETEN,
            chatTemplateArgs = mapOf(
                "temperature" to ChatTemplateKwargValue.of(0.5),
                "label" to ChatTemplateKwargValue.of("x"),
            ),
        )
        val b = body(m, ModelThinkingLevel.HIGH)
        assertEquals(0.5, b["chat_template_args"]!!.jsonObject["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals("x", b["chat_template_args"]!!.jsonObject["label"]!!.jsonPrimitive.content)

        val empty = body(model(ThinkingFormat.BASETEN), ModelThinkingLevel.HIGH)
        assertFalse(empty.containsKey("chat_template_args"))
    }

    @Test
    fun `non-reasoning model sends no thinking params`() {
        val m = model(ThinkingFormat.QWEN).copy(reasoning = false)
        val b = body(m, ModelThinkingLevel.HIGH)
        assertFalse(b.containsKey("enable_thinking"))
        assertFalse(b.containsKey("reasoning_effort"))
    }

    @Test
    fun `openrouter and ant-ling never emit reasoning_effort`() {
        for (format in listOf(ThinkingFormat.OPENROUTER, ThinkingFormat.ANT_LING)) {
            for (effort in listOf(null, ModelThinkingLevel.HIGH)) {
                val b = body(model(format), effort)
                assertFalse(b.containsKey("reasoning_effort"), "$format must not send reasoning_effort")
            }
        }
    }

    // pi test/openai-completions-tool-choice.test.ts "omits reasoning effort for
    // OpenCode Grok Build": the OPENAI format without effort support sends no
    // thinking params at all (also covered against the real catalog asset in
    // OpenAiCompletionsPayloadTest).
    @Test
    fun `openai format without effort support sends no thinking params`() {
        val b = body(model(ThinkingFormat.OPENAI, supportsReasoningEffort = false), ModelThinkingLevel.HIGH)
        assertFalse(b.containsKey("reasoning_effort"))
        assertFalse(b.containsKey("thinking"))
    }

    // TODO(pi test/openai-completions-thinking-token-budget.test.ts @pin, and the
    // chat-template cases in test/openai-completions-tool-choice.test.ts):
    // thinking token budgets (compat.supportsThinkingTokenBudget /
    // thinkingTokenBudgetField → a top-level thinking_token_budget field,
    // clamped to leave answer room via clampThinkingBudgetToAnswerRoom) and the
    // chat-template / qwen-chat-template thinkingFormats (chatTemplateKwargs →
    // chat_template_kwargs, including $var thinking.budget) are unported:
    // no supported catalog model sets them, and pi only reaches them through
    // custom local-vLLM providers, which pathfinder does not have. Sketch of
    // the missing cases:
    //
    // fun `sends the configured budget for the requested level`() {
    //     val m = vllmModel(compat = OpenAiCompletionsCompat(
    //         thinkingFormat = ThinkingFormat.ZAI, supportsThinkingTokenBudget = true))
    //     val b = body(m, effort = MEDIUM, thinkingBudgets = {MEDIUM to 4096})
    //     assertEquals(4096L, b["thinking_token_budget"]!!.jsonPrimitive.longOrNull)
    // }
    //
    // fun `qwen chat template kwargs carry enable_thinking and preserve_thinking`() {
    //     val b = body(model(ThinkingFormat.QWEN_CHAT_TEMPLATE), effort = HIGH)
    //     assertEquals(
    //         parse("{"enable_thinking":true,"preserve_thinking":true}"),
    //         b["chat_template_kwargs"],
    //     )
    // }
    //
    // Port these if custom providers ever land (they need OpenAiCompletionsCompat
    // fields and ThinkingFormat members that do not exist yet).
}
