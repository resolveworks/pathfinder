package works.resolve.pathfinder.codingagent.core

import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingLevelMap
import works.resolve.pathfinder.ai.UserMessage

/**
 * Characterization of createAgentSession against pi's sdk.ts ordering:
 * fold → model restore/fallback → findInitialModel → thinking resolution
 * and clamping → agent construction → restoration → new-session seeding.
 */
class CreateAgentSessionTest {

    private val model = Model(
        id = "model-a",
        name = "A",
        api = "openai-completions",
        provider = "provider-a",
        baseUrl = "https://a.example.invalid",
        reasoning = true
    )

    /** pi's xhigh/max map shape (explicit null = unsupported). */
    private val extendedModel = model.copy(
        id = "model-extended",
        thinkingLevelMap = ThinkingLevelMap.of(
            ModelThinkingLevel.OFF to null,
            ModelThinkingLevel.MINIMAL to null,
            ModelThinkingLevel.LOW to "low",
            ModelThinkingLevel.MEDIUM to null,
            ModelThinkingLevel.HIGH to "high",
            ModelThinkingLevel.XHIGH to null,
            ModelThinkingLevel.MAX to "max"
        )
    )

    private val otherModel = model.copy(id = "model-b")

    private fun assistant(m: Model, text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = m.api,
        provider = m.provider,
        model = m.id,
        stopReason = StopReason.STOP,
        timestamp = 1L
    )

    private val idleStream: StreamFn = StreamFn { _, _, _ -> flow { } }

    private fun provider(id: String, models: List<Model>, authenticated: Boolean = true): Provider =
        Provider(
            id = id,
            name = id,
            baseUrl = "https://$id.example.invalid",
            authResolver = if (authenticated) {
                { _, _ -> ResolvedAuth(apiKey = "k") }
            } else {
                { _, _ -> null }
            },
            models = models,
            apis = emptyMap()
        )

    private suspend fun newManager(): SessionManager = SessionManager.create(
        createTempDirectory("factory-test").toFile(),
        ioDispatcher = Dispatchers.Unconfined
    )

    private suspend fun create(
        manager: SessionManager,
        settings: Settings = Settings(),
        models: Models = Models(listOf(provider(model.provider, listOf(model, otherModel)))),
        streamFn: StreamFn = idleStream
    ): CreateAgentSessionResult = createAgentSession(
        manager = manager,
        settingsManager = SettingsManager.inMemory(settings),
        models = models,
        streamFn = streamFn
    )

    // ---- new-session seeding (sdk.ts: "Save initial model and thinking level") ----

    @Test
    fun `a fresh session seeds model_change and thinking_level_change entries`() = runTest {
        val manager = newManager()
        val result = create(
            manager,
            settings = Settings(
                defaultProvider = model.provider,
                defaultModel = model.id,
                defaultThinkingLevel = ModelThinkingLevel.HIGH
            )
        )

        assertEquals(model, result.session.model)
        assertEquals(ModelThinkingLevel.HIGH, result.session.thinkingLevel)
        val entries = manager.getEntries()
        val modelChange = entries.filterIsInstance<ModelChangeEntry>().single()
        assertEquals(model.provider, modelChange.provider)
        assertEquals(model.id, modelChange.modelId)
        val thinking = entries.filterIsInstance<ThinkingLevelEntry>().single()
        assertEquals("high", thinking.thinkingLevel)
        assertNull(result.modelFallbackMessage)
    }

    @Test
    fun `a fresh session without a default thinking level seeds medium`() = runTest {
        val result =
            create(
                manager = newManager(),
                settings = Settings(defaultProvider = model.provider, defaultModel = model.id)
            )

        assertEquals(ModelThinkingLevel.MEDIUM, result.session.thinkingLevel)
        assertEquals(
            "medium",
            result.session.sessionManager.getEntries()
                .filterIsInstance<ThinkingLevelEntry>().single().thinkingLevel
        )
    }

    // ---- branch restoration (sdk.ts: "Restore messages if session has existing data") ----

    @Test
    fun `an existing branch restores messages, model, and thinking without duplicate entries`() =
        runTest {
            val manager = newManager()
            manager.appendMessage(UserMessage.ofText("hello", 1L))
            manager.appendMessage(assistant(otherModel, "world"))
            manager.appendThinkingLevelChange("low")

            val result = create(
                manager,
                settings = Settings(defaultProvider = model.provider, defaultModel = model.id)
            )

            // The assistant message's provider/model is what ran: the fold
            // restores model-b over the settings default.
            assertEquals(otherModel, result.session.model)
            assertEquals(ModelThinkingLevel.LOW, result.session.thinkingLevel)
            assertEquals(2, result.session.state.value.messages.size)
            assertEquals(
                1,
                manager.getEntries().filterIsInstance<ThinkingLevelEntry>().size
            )
            assertNull(result.modelFallbackMessage)
        }

    @Test
    fun `an existing branch without a thinking entry appends the resolved level`() = runTest {
        val manager = newManager()
        manager.appendMessage(UserMessage.ofText("hello", 1L))
        manager.appendMessage(assistant(model, "world"))

        val result = create(
            manager,
            settings = Settings(
                defaultProvider = model.provider,
                defaultModel = model.id,
                defaultThinkingLevel = ModelThinkingLevel.HIGH
            )
        )

        assertEquals(ModelThinkingLevel.HIGH, result.session.thinkingLevel)
        assertEquals(
            listOf("high"),
            manager.getEntries().filterIsInstance<ThinkingLevelEntry>()
                .map { it.thinkingLevel }
        )
    }

    // ---- model fallback (sdk.ts restore + findInitialModel) ----

    @Test
    fun `an unresolvable branch model falls back with a message`() = runTest {
        val manager = newManager()
        manager.appendMessage(UserMessage.ofText("hello", 1L))
        manager.appendMessage(assistant(model.copy(id = "gone"), "world"))

        val result = create(
            manager,
            settings = Settings(defaultProvider = model.provider, defaultModel = model.id)
        )

        assertEquals(model, result.session.model)
        assertEquals(
            "Could not restore model provider-a/gone. Using provider-a/model-a",
            result.modelFallbackMessage
        )
    }

    @Test
    fun `an unauthenticated branch model falls back with a message`() = runTest {
        val manager = newManager()
        manager.appendMessage(UserMessage.ofText("hello", 1L))
        manager.appendMessage(assistant(otherModel, "world"))

        // No provider is authenticated: pi would carry an undefined model;
        // Pathfinder's Agent requires one, so creation is refused.
        val error = runCatching {
            create(
                manager,
                settings = Settings(defaultProvider = model.provider, defaultModel = model.id),
                models = Models(
                    listOf(
                        provider(model.provider, listOf(model, otherModel), authenticated = false)
                    )
                )
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("No models available.", error!!.message)
    }

    // ---- thinking clamping to model capabilities (sdk.ts clampThinkingLevel step) ----

    @Test
    fun `the resolved level clamps to the model's map`() = runTest {
        val result = create(
            manager = newManager(),
            settings = Settings(
                defaultProvider = extendedModel.provider,
                defaultModel = extendedModel.id,
                defaultThinkingLevel = ModelThinkingLevel.MEDIUM
            ),
            models = Models(listOf(provider(extendedModel.provider, listOf(extendedModel))))
        )

        // medium is explicitly unsupported: it rounds up to high.
        assertEquals(ModelThinkingLevel.HIGH, result.session.thinkingLevel)
        assertEquals(
            "high",
            result.session.sessionManager.getEntries()
                .filterIsInstance<ThinkingLevelEntry>().single().thinkingLevel
        )
    }

    // ---- scoped models (pi's --models rule via enabledModels patterns) ----

    @Test
    fun `a fresh session prefers a saved default inside the scope`() = runTest {
        val result = create(
            manager = newManager(),
            settings = Settings(
                defaultProvider = model.provider,
                defaultModel = model.id,
                enabledModels = listOf(
                    "${otherModel.provider}/${otherModel.id}",
                    "${model.provider}/${model.id}"
                )
            )
        )

        assertEquals(model, result.session.model)
        assertEquals(listOf(otherModel, model), result.session.scopedModels.map { it.model })
    }

    @Test
    fun `a saved default outside the scope falls back to the first scoped model`() = runTest {
        val result = create(
            manager = newManager(),
            settings = Settings(
                defaultProvider = otherModel.provider,
                defaultModel = otherModel.id,
                enabledModels = listOf("${model.provider}/${model.id}")
            )
        )

        assertEquals(model, result.session.model)
    }

    @Test
    fun `a scoped pattern thinking level is the initial level`() = runTest {
        val result = create(
            manager = newManager(),
            settings = Settings(
                defaultThinkingLevel = ModelThinkingLevel.LOW,
                enabledModels = listOf("${model.provider}/${model.id}:high")
            )
        )

        assertEquals(model, result.session.model)
        assertEquals(ModelThinkingLevel.HIGH, result.session.thinkingLevel)
        assertEquals(
            "high",
            result.session.sessionManager.getEntries()
                .filterIsInstance<ThinkingLevelEntry>().single().thinkingLevel
        )
    }

    @Test
    fun `a continuing session ignores the scope and restores from the branch`() = runTest {
        val manager = newManager()
        manager.appendMessage(UserMessage.ofText("hello", 1L))
        manager.appendMessage(assistant(model, "world"))

        val result = create(
            manager,
            settings = Settings(
                defaultProvider = model.provider,
                defaultModel = model.id,
                enabledModels = listOf("${otherModel.provider}/${otherModel.id}")
            )
        )

        assertEquals(model, result.session.model)
    }
}

/** Ports of pi's model-resolver.test.ts findInitialModel cases (CLI-only ones skipped). */
class FindInitialModelTest {

    private val savedModel = Model(
        id = "deepseek-v4-flash",
        name = "DeepSeek V4 Flash",
        api = "openai-completions",
        provider = "deepseek",
        baseUrl = "https://api.deepseek.test",
        reasoning = true
    )

    private val localModel = savedModel.copy(
        provider = "spark-two",
        baseUrl = "http://spark-two.test/v1"
    )

    private val aiGatewayModel = savedModel.copy(
        id = "anthropic/claude-opus-4-6",
        provider = "vercel-ai-gateway"
    )

    private fun provider(id: String, models: List<Model>, authenticated: Boolean = true): Provider =
        Provider(
            id = id,
            name = id,
            baseUrl = "https://$id.example.invalid",
            authResolver = if (authenticated) {
                { _, _ -> ResolvedAuth(apiKey = "k") }
            } else {
                { _, _ -> null }
            },
            models = models,
            apis = emptyMap()
        )

    private suspend fun find(
        models: Models,
        scopedModels: List<ScopedModel> = emptyList(),
        isContinuing: Boolean = false,
        defaultProvider: String? = null,
        defaultModelId: String? = null,
        defaultThinkingLevel: ModelThinkingLevel? = null,
        modelThinkingLevels: Map<String, ModelThinkingLevel> = emptyMap()
    ): InitialModelResult = findInitialModel(
        scopedModels = scopedModels,
        isContinuing = isContinuing,
        defaultProvider = defaultProvider,
        defaultModelId = defaultModelId,
        defaultThinkingLevel = defaultThinkingLevel,
        modelThinkingLevels = modelThinkingLevels,
        models = models
    )

    /** pi: "findInitialModel ignores an unauthenticated saved default". */
    @Test
    fun `ignores an unauthenticated saved default`() = runTest {
        val models = Models(
            listOf(
                provider("deepseek", listOf(savedModel), authenticated = false),
                provider("spark-two", listOf(localModel))
            )
        )

        val result = find(
            models,
            defaultProvider = "deepseek",
            defaultModelId = "deepseek-v4-flash"
        )

        assertEquals("spark-two", result.model?.provider)
        assertEquals("deepseek-v4-flash", result.model?.id)
    }

    /** pi: "findInitialModel selects ai-gateway default when available" —
     *  no table entry matches, so the single available model is picked via
     *  the first-available fallback (as upstream: its mock registry also
     *  holds only that model). */
    @Test
    fun `selects the per-provider preferred model when available`() = runTest {
        val models = Models(listOf(provider("vercel-ai-gateway", listOf(aiGatewayModel))))

        val result = find(models)

        assertEquals("vercel-ai-gateway", result.model?.provider)
        assertEquals("anthropic/claude-opus-4-6", result.model?.id)
    }

    @Test
    fun `the settings default beats the provider defaults`() = runTest {
        val models = Models(
            listOf(
                provider("deepseek", listOf(savedModel)),
                provider("spark-two", listOf(localModel))
            )
        )

        val result = find(
            models,
            defaultProvider = "spark-two",
            defaultModelId = "deepseek-v4-flash",
            defaultThinkingLevel = ModelThinkingLevel.LOW
        )

        assertEquals("spark-two", result.model?.provider)
        assertEquals(ModelThinkingLevel.LOW, result.thinkingLevel)
    }

    @Test
    fun `a per-model override beats the global default for the saved default`() = runTest {
        val models = Models(listOf(provider("deepseek", listOf(savedModel))))

        val result = find(
            models,
            defaultProvider = "deepseek",
            defaultModelId = "deepseek-v4-flash",
            defaultThinkingLevel = ModelThinkingLevel.LOW,
            modelThinkingLevels =
                mapOf("deepseek/deepseek-v4-flash" to ModelThinkingLevel.HIGH)
        )

        assertEquals(ModelThinkingLevel.HIGH, result.thinkingLevel)
    }

    @Test
    fun `no authenticated provider yields no model`() = runTest {
        val models = Models(
            listOf(provider("deepseek", listOf(savedModel), authenticated = false))
        )

        val result =
            find(models, defaultProvider = "deepseek", defaultModelId = "deepseek-v4-flash")

        assertNull(result.model)
        assertEquals(ModelThinkingLevel.MEDIUM, result.thinkingLevel)
    }

    @Test
    fun `the first scoped model wins with its explicit level`() = runTest {
        val models = Models(
            listOf(provider("deepseek", listOf(savedModel, localModel.copy(provider = "deepseek"))))
        )

        val result = find(
            models,
            scopedModels = listOf(
                ScopedModel(localModel.copy(provider = "deepseek"), ModelThinkingLevel.HIGH)
            ),
            defaultProvider = "deepseek",
            defaultModelId = "deepseek-v4-flash",
            defaultThinkingLevel = ModelThinkingLevel.LOW
        )

        assertEquals("deepseek-v4-flash", result.model?.id)
        assertEquals(ModelThinkingLevel.HIGH, result.thinkingLevel)
    }
}

/** Minimal characterization of the enabledModels pattern resolution (pi's resolveModelScopeFromModels). */
class ResolveModelScopeTest {

    private val zai = Model(
        id = "glm-5.3",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://z.example.invalid"
    )

    private val openRouterModel = zai.copy(id = "openai/gpt-x", provider = "openrouter")

    private val available = listOf(zai, zai.copy(id = "glm-5.2"), openRouterModel)

    @Test
    fun `an exact canonical reference resolves`() {
        val result = resolveModelScope(listOf("zai/glm-5.3"), available)
        assertEquals(listOf("glm-5.3"), result.scopedModels.map { it.model.id })
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `a glob matches provider-slash-id and bare ids case-insensitively`() {
        val result = resolveModelScope(listOf("*GLM-5*"), available)
        assertEquals(listOf("glm-5.3", "glm-5.2"), result.scopedModels.map { it.model.id })
    }

    @Test
    fun `a thinking-level suffix attaches to the resolved models`() {
        val result = resolveModelScope(listOf("zai/glm-5.3:high"), available)
        assertEquals(
            listOf(ModelThinkingLevel.HIGH),
            result.scopedModels.map { it.thinkingLevel }
        )
    }

    @Test
    fun `a model id containing a colon survives an invalid level suffix`() {
        val result = resolveModelScope(listOf("openrouter/openai/gpt-x:not-a-level"), available)
        assertEquals(listOf("openai/gpt-x"), result.scopedModels.map { it.model.id })
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun `a non-matching pattern diagnoses and adds nothing`() {
        val result = resolveModelScope(listOf("nope"), available)
        assertTrue(result.scopedModels.isEmpty())
        assertEquals(listOf("No models match pattern \"nope\""), result.diagnostics)
    }
}
