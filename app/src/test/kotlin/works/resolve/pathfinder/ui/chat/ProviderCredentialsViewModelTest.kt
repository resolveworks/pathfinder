package works.resolve.pathfinder.ui.chat

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.Description
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt as AuthInteractionPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.CredentialInfo
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.CredentialType
import works.resolve.pathfinder.ai.auth.MapCatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.NoopAuthContext
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.transport.HttpStreamingTransport
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.transport.TransportResponse
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.codingagent.core.session.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry
import works.resolve.pathfinder.codingagent.core.session.SessionError
import works.resolve.pathfinder.codingagent.core.session.SessionErrorCode
import works.resolve.pathfinder.codingagent.core.session.SessionInfo
import works.resolve.pathfinder.codingagent.core.session.SessionManager
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry
import works.resolve.pathfinder.data.sessions.SessionSource
import works.resolve.pathfinder.data.settings.ModelSettings
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.data.settings.SettingsStore
import works.resolve.pathfinder.runtime.AgentFactory
import works.resolve.pathfinder.runtime.NativeAgentFactory
import works.resolve.pathfinder.runtime.catalogAuthResolver
import works.resolve.pathfinder.tools.webfetch.WebFetchTool
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.tools.websearch.SearchProviderService

/** LLM-provider accounts: API-key and OAuth credential flows, auth methods, and the credential-derived provider/model surfaces. */
internal class ProviderCredentialsViewModelTest : ChatHarnessTest() {

    @Test
    fun unconfiguredInit_showsNeedsConfiguration_andKeepsKeyPrivate() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()

            val state = vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            assertEquals(ProvidersNavKey, state.startKey)
            assertFalse(state.providerOptions.first { o -> o.id == "zai" }.configured)
            assertNull(state.activeSessionId)
            assertTrue(state.messages.isEmpty())
            assertTrue(state.modelOptions.isEmpty())

            // A stored key with no model settings: the initial model is derived
            // (first available of a configured provider) and the app enters the
            // chat directly — while the key never appears anywhere in the UI state.
            h.credentials.creds["zai"] = ApiKeyCredential("SECRET-KEY-123")
            val vm2 = h.newViewModel()
            val state2 = vm2.awaitState { it.status == ChatStatus.Ready }
            assertTrue(state2.providerOptions.first { o -> o.id == "zai" }.configured)
            assertEquals("glm-4.7", state2.selectedModel?.modelId)
            assertNotNull(state2.activeSessionId)
            assertFalse(state2.toString().contains("SECRET-KEY-123"))

            vm.closeForTest()
            vm2.closeForTest()
        }

    @Test
    fun credentialSave_success_bumpsSuccessEpoch_failedOrIncompleteDoesNot() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            vm.saveProviderCredential("zai", "   ", emptyMap())
            vm.awaitState { it.error != null }
            assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
            vm.dismissError()

            h.credentials.failWrites = true
            vm.saveProviderCredential("zai", "k", emptyMap())
            vm.awaitState { it.error != null }
            assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
            assertNull(h.credentials.creds["zai"])
            vm.dismissError()
            h.credentials.failWrites = false

            vm.saveProviderCredential("zai", "k", emptyMap())
            vm.awaitState { it.credentialSuccessEpoch == 1L }
            assertEquals("k", h.storedApiKey("zai"))

            vm.saveProviderCredential("zai", "k2", emptyMap())
            vm.awaitState { it.credentialSuccessEpoch == 2L }
            assertEquals("k2", h.storedApiKey("zai"))

            vm.closeForTest()
        }

    @Test
    fun blankKeySave_isRejected_andCompleteSaveReplacesStoredKey() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "first-key")
            vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals("first-key", h.storedApiKey("zai"))

            // A blank key is a missing required value (logins replace wholesale):
            // rejected with an error naming the missing prompt — never its value —
            // leaving the stored credential untouched.
            vm.saveProviderCredential("zai", "   ", emptyMap())
            vm.awaitState { it.error != null }
            val state = vm.uiState.value
            val error = checkNotNull(state.error)
            assertTrue(error.contains("API key"))
            assertFalse(error.contains("first-key"))
            assertFalse(state.toString().contains("first-key"))
            assertEquals(ChatStatus.Ready, state.status)
            assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
            assertEquals("first-key", h.storedApiKey("zai"))
            vm.dismissError()

            vm.saveProviderCredential("zai", "second-key", emptyMap())
            vm.awaitState { it.credentialSuccessEpoch == 2L }
            assertEquals("second-key", h.storedApiKey("zai"))
            assertEquals(1, h.createdAgents.size)

            vm.closeForTest()
        }

    @Test
    fun storedCredential_survivesFailedReSave_completeRetryReplaces() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            h.rejectedModelIds += "glm-5.3"
            vm.saveProviderCredential("zai", "first-key", emptyMap())
            // The derived initial model is unaffected by the rejection.
            vm.awaitState { it.status == ChatStatus.Ready }
            val state = vm.uiState.value
            assertEquals("first-key", h.storedApiKey("zai"))
            assertTrue(state.providerOptions.first { o -> o.id == "zai" }.configured)
            assertFalse(state.toString().contains("first-key"))

            vm.selectModel("zai", "glm-5.3")
            vm.awaitState { it.error != null }
            assertEquals("glm-4.7", vm.uiState.value.selectedModel?.modelId)
            vm.dismissError()

            // An incomplete re-save (blank key: logins re-prompt everything,
            // nothing is merged) is rejected; the stored credential survives.
            vm.saveProviderCredential("zai", "  ", emptyMap())
            vm.awaitState { it.error != null }
            assertFalse(checkNotNull(vm.uiState.value.error).contains("first-key"))
            assertEquals("first-key", h.storedApiKey("zai"))
            assertFalse(vm.uiState.value.toString().contains("first-key"))
            vm.dismissError()

            vm.saveProviderCredential("zai", "second-key", emptyMap())
            vm.awaitState { it.credentialSuccessEpoch == 2L }
            assertEquals("second-key", h.storedApiKey("zai"))
            assertEquals(ChatStatus.Ready, vm.uiState.value.status)

            vm.closeForTest()
        }

    @Test
    fun providerAndModelOptions_followCredentialState() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        val state = vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

        // All catalog providers listed, all unconfigured: only configured
        // providers contribute model options.
        assertEquals(
            listOf("Cloudflare AI Gateway", "GitHub Copilot", "OAuth Only", "OpenAI", "Z.AI"),
            state.providerOptions.map { it.name }
        )
        assertTrue(state.providerOptions.none { it.configured })
        assertTrue(state.modelOptions.isEmpty())

        vm.saveProviderCredential("zai", "SECRET-KEY-777", emptyMap())
        val after = vm.awaitState { it.status == ChatStatus.Ready }
        assertTrue(
            after.providerOptions.first {
                it.id == "cloudflare-ai-gateway"
            }.let { !it.configured }
        )
        assertTrue(after.modelOptions.isNotEmpty())
        assertTrue(after.modelOptions.all { it.providerId == "zai" })
        assertEquals("GLM-4.7", after.modelOptions.first { it.modelId == "glm-4.7" }.name)
        assertEquals("glm-4.7", after.selectedModel?.modelId)
        assertEquals(after.modelOptions, after.scopedModelOptions)
        assertNull(after.enabledModels)
        assertFalse(after.toString().contains("SECRET-KEY-777"))

        vm.saveProviderCredential(
            "cloudflare-ai-gateway",
            "cf",
            mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw")
        )
        val both = vm.awaitState {
            it.providerOptions.first { o ->
                o.id ==
                    "cloudflare-ai-gateway"
            }.configured
        }
        assertTrue(
            both.modelOptions.any {
                it.providerId == "cloudflare-ai-gateway" &&
                    it.modelId == "workers-ai/test-model"
            }
        )
        // Provider-name-then-model-name sort.
        assertEquals("Cloudflare AI Gateway", both.modelOptions.first().providerName)

        vm.closeForTest()
    }

    @Test
    fun saveProviderCredential_replacesCredentialWholesale() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            // A key-only save is incomplete for Cloudflare (account/gateway ids
            // are required too); the error names the missing prompts — never the
            // submitted values.
            vm.saveProviderCredential("cloudflare-ai-gateway", "cf-key", emptyMap())
            val state = vm.awaitState { it.error != null }
            val error = checkNotNull(state.error)
            assertTrue(error.contains("account ID"))
            assertTrue(error.contains("gateway ID"))
            assertFalse(error.contains("cf-key"))
            assertNull(h.credentials.creds["cloudflare-ai-gateway"])
            assertFalse(
                vm.uiState.value.providerOptions.first { o ->
                    o.id == "cloudflare-ai-gateway"
                }.configured
            )
            vm.dismissError()

            vm.saveProviderCredential(
                "cloudflare-ai-gateway",
                "cf-key",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw")
            )
            vm.awaitState {
                it.providerOptions.first { o -> o.id == "cloudflare-ai-gateway" }.configured
            }
            val filled = h.credentials.creds["cloudflare-ai-gateway"] as ApiKeyCredential
            assertEquals("cf-key", filled.key)
            assertEquals(
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw"),
                filled.env
            )

            // A complete re-save fully replaces key and env — no stale values
            // survive.
            vm.saveProviderCredential(
                "cloudflare-ai-gateway",
                "cf-key-2",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc-2", "CLOUDFLARE_GATEWAY_ID" to "gw-2")
            )
            vm.awaitState { it.credentialSuccessEpoch == 2L }
            val rotated = h.credentials.creds["cloudflare-ai-gateway"] as ApiKeyCredential
            assertEquals("cf-key-2", rotated.key)
            assertEquals(
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc-2", "CLOUDFLARE_GATEWAY_ID" to "gw-2"),
                rotated.env
            )

            // An incomplete re-save (replace semantics, nothing merged from the
            // stored credential) is rejected; the old credential is untouched.
            vm.saveProviderCredential(
                "cloudflare-ai-gateway",
                "cf-key-3",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc-3")
            )
            vm.awaitState { it.error != null }
            val retryError = checkNotNull(vm.uiState.value.error)
            assertTrue(retryError.contains("gateway ID"))
            assertFalse(retryError.contains("cf-key"))
            assertFalse(retryError.contains("acc-3"))
            assertEquals(rotated, h.credentials.creds["cloudflare-ai-gateway"])
            vm.dismissError()

            vm.saveProviderCredential("zai", "   ", emptyMap())
            vm.awaitState { it.error != null }
            assertFalse(vm.uiState.value.providerOptions.first { o -> o.id == "zai" }.configured)
            vm.dismissError()

            h.credentials.failWrites = true
            vm.saveProviderCredential("zai", "k", emptyMap())
            vm.awaitState { it.error != null }
            vm.closeForTest()
        }

    @Test
    fun saveProviderCredential_withValidSettings_adoptsSession_andGoesReady() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            // Valid model settings persisted, but the key is missing: logging in
            // completes configuration.
            h.settings.setProviderId("zai")
            h.settings.setModelId("glm-4.7")

            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            assertNull(vm.uiState.value.activeSessionId)

            vm.saveProviderCredential("zai", "k", emptyMap())
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(ChatNavKey, state.startKey)
            assertTrue(state.navigationEpoch >= 1L)
            assertNotNull(state.activeSessionId)
            assertEquals("zai", state.selectedModel?.providerId)
            assertTrue(state.modelOptions.all { it.providerId == "zai" })
            assertEquals("glm-4.7", state.selectedModel?.modelId)

            vm.closeForTest()
        }

    @Test
    fun saveProviderCredential_withoutModelSettings_derivesInitialModel_andGoesReady() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            assertEquals(ProvidersNavKey, vm.uiState.value.startKey)

            vm.saveProviderCredential("zai", "k", emptyMap())
            val ready = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(ChatNavKey, ready.startKey)
            assertTrue(ready.navigationEpoch >= 1L)
            assertNotNull(ready.activeSessionId)
            assertEquals(0, h.countSessions())
            assertEquals("zai", ready.selectedModel?.providerId)
            assertEquals("glm-4.7", ready.selectedModel?.modelId)
            assertTrue(ready.modelOptions.all { it.providerId == "zai" })

            vm.saveProviderCredential(
                "cloudflare-ai-gateway",
                "cf",
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc", "CLOUDFLARE_GATEWAY_ID" to "gw")
            )
            val both = vm.awaitState {
                it.modelOptions.any { o ->
                    o.providerId ==
                        "cloudflare-ai-gateway"
                }
            }
            assertEquals(ChatStatus.Ready, both.status)
            assertEquals(ChatNavKey, both.startKey)

            vm.closeForTest()
        }

    @Test
    fun unconfiguredInit_withStoredCredential_entersChatWithDerivedModel() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.credentials.creds["zai"] = ApiKeyCredential("stored-key")

            val vm = h.newViewModel()
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(ChatNavKey, state.startKey)
            assertTrue(state.modelOptions.isNotEmpty())
            assertTrue(state.modelOptions.all { it.providerId == "zai" })
            assertTrue(state.providerOptions.first { it.id == "zai" }.configured)
            assertEquals(AuthType.API_KEY, state.providerOptions.first { it.id == "zai" }.authType)
            assertFalse(state.toString().contains("stored-key"))
            assertNotNull(state.activeSessionId)
            assertEquals("glm-4.7", state.selectedModel?.modelId)
            // The derivation seeds the session with a buffered model_change;
            // the file appears only at the first assistant commit.
            waitUntil {
                h.sessions.managers[state.activeSessionId!!]!!.conversation.entries.isNotEmpty()
            }
            val seeded = h.sessions.managers[state.activeSessionId!!]!!.conversation
            val change = seeded.entries.filterIsInstance<ModelChangeEntry>().single()
            assertEquals("zai", change.provider)
            assertEquals("glm-4.7", change.modelId)
            assertNull(h.sessions.stored(state.activeSessionId!!))

            vm.closeForTest()
        }

    @Test
    fun removeProviderCredential_unconfigures_butNeverTearsDownSessions() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            vm.configure(apiKey = "k")
            vm.awaitState { it.status == ChatStatus.Ready }
            val agentsBefore = h.createdAgents.size

            vm.removeProviderCredential("zai")
            val state = vm.awaitState {
                !it.providerOptions.first { o -> o.id == "zai" }.configured
            }
            // Credentials are read per request: status stays Ready and the agent
            // is untouched.
            assertEquals(ChatStatus.Ready, state.status)
            assertEquals(agentsBefore, h.createdAgents.size)
            assertNotNull(state.activeSessionId)
            assertFalse(state.providerOptions.first { o -> o.id == "zai" }.configured)
            assertTrue(state.modelOptions.isEmpty())
            // The live session model stays visible for the model chip.
            assertEquals("glm-4.7", state.selectedModel?.modelId)
            assertNull(h.credentials.creds["zai"])

            h.scriptedStreams.add(
                h.gatedStream(
                    "world",
                    CompletableDeferred<Unit>().apply {
                        complete(Unit)
                    }
                )
            )
            vm.onDraftChange("Hello")
            vm.send()
            vm.awaitState { !it.isStreaming && it.messages.size == 2 }

            vm.saveProviderCredential("zai", "k2", emptyMap())
            vm.awaitState { it.providerOptions.first { o -> o.id == "zai" }.configured }

            vm.closeForTest()
        }

    // ---- provider auth methods & interactive account login ----

    @Test
    fun authMethods_apiKeyOnly_bothMethods_oauthOnly_andScreenModes() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            val cloudflare = vm.providerAuthMethods("cloudflare-ai-gateway")
            assertEquals(listOf(AuthType.API_KEY), cloudflare.map { it.type })
            assertEquals("Cloudflare API key", cloudflare.single().label)
            assertFalse(cloudflare.single().isSubscription)

            val zai = vm.providerAuthMethods("zai")
            assertEquals(listOf(AuthType.API_KEY, AuthType.OAUTH), zai.map { it.type })
            assertEquals("Z.AI API key", zai[0].label)
            assertFalse(zai[0].isSubscription)
            assertEquals("Sign in with a Z.AI account", zai[1].label)
            assertTrue(zai[1].isSubscription)

            val only = vm.providerAuthMethods("oauth-only")
            assertEquals(listOf(AuthType.OAUTH), only.map { it.type })
            assertTrue(only.single().isSubscription)

            assertEquals(ProviderAuthScreenMode.API_KEY_FORM, providerAuthScreenMode(cloudflare))
            assertEquals(ProviderAuthScreenMode.METHOD_CHOICE, providerAuthScreenMode(zai))
            assertEquals(ProviderAuthScreenMode.START_OAUTH, providerAuthScreenMode(only))
            assertEquals(ProviderAuthScreenMode.NO_METHODS, providerAuthScreenMode(emptyList()))

            assertTrue(vm.providerAuthMethods("no-such-provider").isEmpty())

            vm.closeForTest()
        }

    @Test
    fun projectAuthPrompt_mapsKinds_metadataOnly() {
        // Prompt metadata crosses the boundary; answers never do.
        assertEquals(
            PendingAuthPrompt(AuthPromptKind.TEXT, "message", "placeholder"),
            projectAuthPrompt(AuthInteractionPrompt.Text("message", "placeholder"))
        )
        assertEquals(
            PendingAuthPrompt(AuthPromptKind.SECRET, "paste token"),
            projectAuthPrompt(AuthInteractionPrompt.Secret("paste token"))
        )
        assertEquals(
            PendingAuthPrompt(AuthPromptKind.MANUAL_CODE, "enter code"),
            projectAuthPrompt(AuthInteractionPrompt.ManualCode("enter code"))
        )
        val select = projectAuthPrompt(
            AuthInteractionPrompt.Select(
                "choose",
                listOf(AuthInteractionPrompt.Select.Option("a", "A", "first"))
            )
        )
        assertEquals(AuthPromptKind.SELECT, select.kind)
        assertEquals(listOf(AuthPromptOption("a", "A", "first")), select.options)
    }

    @Test
    fun storedOAuthCredential_configuresProvider_onlyWithRegisteredFlow() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            // A stored OAuth credential marks the provider configured where a
            // flow is registered (zai)...
            h.credentials.creds["zai"] =
                OAuthCredential("access-token-9", "refresh-token-9", Long.MAX_VALUE)
            // ...but resolves as unconfigured without a handler (cloudflare has
            // no registered flow).
            h.credentials.creds["cloudflare-ai-gateway"] =
                OAuthCredential("access-token-9", "refresh-token-9", Long.MAX_VALUE)

            val vm = h.newViewModel()
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertTrue(state.providerOptions.first { it.id == "zai" }.configured)
            assertEquals(AuthType.OAUTH, state.providerOptions.first { it.id == "zai" }.authType)
            assertFalse(state.providerOptions.first { it.id == "cloudflare-ai-gateway" }.configured)
            assertNull(state.providerOptions.first { it.id == "cloudflare-ai-gateway" }.authType)
            assertTrue(state.modelOptions.all { it.providerId == "zai" })
            assertTrue(state.modelOptions.isNotEmpty())
            assertFalse(state.toString().contains("access-token-9"))

            vm.removeProviderCredential("zai")
            vm.awaitState { !it.providerOptions.first { o -> o.id == "zai" }.configured }
            assertNull(h.credentials.creds["zai"])

            vm.closeForTest()
        }

    @Test
    fun accountLogin_eventAndPromptProgression_successClosesWithEpoch() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }
            var chosen: String? = null
            h.oauthZai.loginFn = { interaction ->
                interaction.notify(AuthEvent.Info("Choose an account"))
                chosen = interaction.prompt(
                    AuthInteractionPrompt.Select(
                        "Select account",
                        listOf(
                            AuthInteractionPrompt.Select.Option("personal", "Personal"),
                            AuthInteractionPrompt.Select.Option("work", "Work", "Company account")
                        )
                    )
                )
                interaction.notify(
                    AuthEvent.AuthUrl("https://auth.test/authorize", "Approve access")
                )
                interaction.notify(
                    AuthEvent.DeviceCode(
                        "ABCD-1234",
                        "https://verify.test/device",
                        intervalSeconds = 5
                    )
                )
                interaction.notify(AuthEvent.Progress("Waiting for approval"))
                val code = interaction.prompt(
                    AuthInteractionPrompt.ManualCode("Enter the code from the browser")
                )
                assertEquals("654321", code)
                OAuthCredential("access-token-1", "refresh-token-1", Long.MAX_VALUE)
            }

            vm.beginProviderAuthLogin("zai", oauthMethod)

            // The Select prompt projects ids/labels/descriptions — never values.
            val selectPending = vm.uiState
                .first { it.authFlow?.pendingPrompt?.kind == AuthPromptKind.SELECT }
                .authFlow!!.pendingPrompt!!
            assertEquals(listOf("personal", "work"), selectPending.options.map { it.id })
            assertEquals(listOf("Personal", "Work"), selectPending.options.map { it.label })
            assertEquals("Company account", selectPending.options[1].description)

            vm.submitAuthPrompt("work")
            assertEquals("work", chosen)

            vm.awaitState { it.authFlow?.pendingPrompt?.kind == AuthPromptKind.MANUAL_CODE }
            val events = vm.uiState.value.authFlow!!.events
            assertTrue(events[0] is AuthEvent.Info)
            assertEquals("https://auth.test/authorize", (events[1] as AuthEvent.AuthUrl).url)
            assertEquals("ABCD-1234", (events[2] as AuthEvent.DeviceCode).userCode)
            assertTrue(events[3] is AuthEvent.Progress)

            vm.submitAuthPrompt("654321")

            // Success: the flow clears, the epoch bumps exactly once (the UI
            // closes the auth screen on it), and no token material ever entered
            // the state.
            val done = vm.awaitState { it.authFlow == null && it.credentialSuccessEpoch == 1L }
            assertTrue(done.providerOptions.first { it.id == "zai" }.configured)
            assertEquals(AuthType.OAUTH, done.providerOptions.first { it.id == "zai" }.authType)
            assertTrue(done.modelOptions.any { it.providerId == "zai" })
            assertFalse(done.toString().contains("access-token-1"))
            assertNull(done.error)

            // The stored credential is the OAuth one (the store was empty before).
            assertEquals(CredentialType.OAUTH, h.credentials.creds["zai"]?.type)

            vm.closeForTest()
        }

    @Test
    fun accountLogin_failure_surfacesSafeError_andClearsFlow() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }

            h.oauthZai.loginFn = { throw IllegalStateException("token exchange failed (400)") }
            vm.beginProviderAuthLogin("zai", oauthMethod)
            val failed = vm.awaitState { it.authFlow == null && it.error != null }
            assertEquals("Could not complete sign-in", failed.error)

            vm.closeForTest()
        }

    @Test
    fun apiKeyLogin_persistsCredential_andBumpsEpoch() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.awaitState { it.credentialSuccessEpoch > 0 }
        assertEquals("k", h.storedApiKey("zai"))

        vm.closeForTest()
    }

    @Test
    fun credentialReadFailure_degradesToNeedsConfiguration() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            // The restoration path must degrade to NeedsConfiguration rather
            // than crash: a failing credential read never blocks startup.
            h.settings.setProviderId("zai")
            h.settings.setModelId(testModel.id)
            h.credentials.failWrites = true
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }

            vm.closeForTest()
        }

    @Test
    fun accountLogin_cancelOrFailure_mutatesNothing_andFlowRestartsCleanly() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }

            h.oauthZai.loginFn = { interaction ->
                interaction.prompt(AuthInteractionPrompt.Secret("Paste token"))
                OAuthCredential("never-stored", "never-stored", Long.MAX_VALUE)
            }
            vm.beginProviderAuthLogin("zai", oauthMethod)
            vm.awaitState { it.authFlow?.pendingPrompt?.kind == AuthPromptKind.SECRET }
            vm.cancelProviderAuthLogin()
            vm.awaitState { it.authFlow == null }
            assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
            assertNull(h.credentials.creds["zai"])
            assertNull(vm.uiState.value.error)

            h.oauthZai.loginFn =
                { throw IllegalStateException("token endpoint returned access-token-2") }
            vm.beginProviderAuthLogin("zai", oauthMethod)
            vm.awaitState { it.authFlow == null && it.error != null }
            assertEquals("Could not complete sign-in", vm.uiState.value.error)
            assertFalse(vm.uiState.value.toString().contains("access-token-2"))
            assertNull(h.credentials.creds["zai"])
            assertEquals(0, vm.uiState.value.credentialSuccessEpoch)
            vm.dismissError()

            h.oauthZai.loginFn =
                { OAuthCredential("access-token-3", "refresh-token-3", Long.MAX_VALUE) }
            vm.beginProviderAuthLogin("zai", oauthMethod)
            vm.awaitState { it.authFlow == null && it.credentialSuccessEpoch == 1L }
            assertTrue(vm.uiState.value.providerOptions.first { o -> o.id == "zai" }.configured)
            assertFalse(vm.uiState.value.toString().contains("access-token-3"))

            vm.closeForTest()
        }

    @Test
    fun concurrentAuthFlows_areRejected() = runTest(mainDispatcherRule.scheduler) {
        val h = harness()
        val vm = h.newViewModel()
        vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
        val oauthMethod = vm.providerAuthMethods("zai").first { it.type == AuthType.OAUTH }

        val promptGate = CompletableDeferred<Unit>()
        h.oauthZai.loginFn = { interaction ->
            interaction.prompt(AuthInteractionPrompt.Text("Enter anything"))
                .also { promptGate.complete(Unit) }
            OAuthCredential("never-stored", "never-stored", Long.MAX_VALUE)
        }
        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.awaitState { it.authFlow?.pendingPrompt != null }

        vm.beginProviderAuthLogin("zai", oauthMethod)
        vm.awaitState { it.error != null }
        vm.dismissError()
        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.awaitState { it.error != null }
        assertNull(h.credentials.creds["zai"])
        vm.dismissError()

        vm.cancelProviderAuthLogin()
        vm.awaitState { it.authFlow == null }
        vm.saveProviderCredential("zai", "k", emptyMap())
        vm.awaitState { it.credentialSuccessEpoch == 1L }
        assertEquals("k", (h.credentials.creds["zai"] as ApiKeyCredential).key)

        vm.closeForTest()
    }

    // ---- GitHub Copilot credential-based model filtering ----

    @Test
    fun copilotOAuthAvailableModelIds_narrowsModelOptions() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
            val vm = h.newViewModel()

            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(listOf("gpt-4.1"), vm.copilotModelOptions())
            assertTrue(state.providerOptions.first { it.id == "github-copilot" }.configured)

            vm.closeForTest()
        }

    @Test
    fun copilotOAuthMalformedAvailableModelIds_showsAllModels() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            // Mixed (string + number) array: not entirely strings, so the full
            // static list applies.
            h.credentials.creds["github-copilot"] = copilotCredential(
                JsonArray(listOf(JsonPrimitive("gpt-4.1"), JsonPrimitive(7)))
            )
            val vm = h.newViewModel()

            vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals(listOf("claude-haiku-4.5", "gpt-4.1", "gpt-4.5"), vm.copilotModelOptions())

            vm.closeForTest()
        }

    @Test
    fun copilotOAuthEmptyAvailableModelIds_showsNoModelsButStaysConfigured() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray())
            val vm = h.newViewModel()

            val state = vm.awaitState { it.status == ChatStatus.NeedsConfiguration }
            assertEquals(emptyList<String>(), vm.copilotModelOptions())
            assertTrue(state.providerOptions.first { it.id == "github-copilot" }.configured)

            vm.closeForTest()
        }

    @Test
    fun copilotLogoutThenApiKeySwitch_showsAllModelsAgain() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
            val vm = h.newViewModel()
            vm.awaitState { it.status == ChatStatus.Ready }

            // Logout: no credential ⇒ unconfigured ⇒ no model options at all
            // (not even unfiltered ones).
            vm.removeProviderCredential("github-copilot")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals(emptyList<String>(), vm.copilotModelOptions())
            assertFalse(
                vm.uiState.value.providerOptions.first {
                    it.id == "github-copilot"
                }.configured
            )

            // An API-key credential is complete and never filtered ⇒ every
            // static model returns.
            h.credentials.creds["github-copilot"] = ApiKeyCredential(key = "tok")
            vm.refreshProviderStatus()
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals(listOf("claude-haiku-4.5", "gpt-4.1", "gpt-4.5"), vm.copilotModelOptions())

            vm.closeForTest()
        }

    @Test
    fun persistedCopilotSelectionUnavailable_derivesAvailableModel_andSurfacesError() =
        runTest(mainDispatcherRule.scheduler) {
            val h = harness()
            h.credentials.creds["github-copilot"] = copilotCredential(stringArray("gpt-4.1"))
            h.settings.setProviderId("github-copilot")
            h.settings.setModelId("gpt-4.5")
            val vm = h.newViewModel()

            // The saved default is credential-filtered out: a safe
            // availability error surfaces, but the derived replacement runs —
            // chat is usable.
            val state = vm.awaitState { it.status == ChatStatus.Ready }
            assertEquals("gpt-4.1", state.selectedModel?.modelId)
            assertEquals(ChatNavKey, state.startKey)
            assertNotNull(state.error)
            assertEquals(listOf("gpt-4.1"), vm.copilotModelOptions())
            vm.dismissError()

            vm.selectModel("github-copilot", "gpt-4.5")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertEquals("Unknown model", vm.uiState.value.error)
            vm.dismissError()

            vm.selectModel("github-copilot", "gpt-4.1")
            mainDispatcherRule.scheduler.advanceUntilIdle()
            assertNull(vm.uiState.value.error)
            assertEquals("gpt-4.1", vm.uiState.value.selectedModel?.modelId)

            vm.closeForTest()
        }
}
