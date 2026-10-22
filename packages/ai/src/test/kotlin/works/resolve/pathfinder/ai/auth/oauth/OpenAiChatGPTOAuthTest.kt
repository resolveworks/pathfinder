package works.resolve.pathfinder.ai.auth.oauth

import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.LoginOptions
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.utils.formQuery

class OpenAiChatGPTOAuthTest {

    private val deviceId = "e61bbe28-07ef-466d-8e5d-a344f94ab305"
    private val requiredScope =
        "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    private class FakeHttpClient(var respond: suspend (OAuthHttpRequest) -> OAuthHttpResponse) :
        OAuthHttpClient {
        val requests = mutableListOf<OAuthHttpRequest>()

        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += request
            return respond(request)
        }
    }

    private fun tokenResponse(
        scope: String = "openid profile email offline_access resource.invoke " +
            "chatgpt.tokens.use.direct",
        access: String = "access-token",
        refresh: String = "refresh-token"
    ) = OAuthHttpResponse(
        200,
        emptyMap(),
        (
            "{\"access_token\":\"$access\",\"refresh_token\":\"$refresh\"," +
                "\"expires_in\":3600,\"id_token\":\"id-token\",\"scope\":\"$scope\"}"
            ).toByteArray()
    )

    private class Interaction(val onManual: suspend (AuthPrompt.ManualCode) -> String) :
        AuthInteraction {
        var authUrl: String? = null

        override suspend fun prompt(prompt: AuthPrompt): String = when (prompt) {
            is AuthPrompt.ManualCode -> onManual(prompt)
            else -> error("unexpected prompt: $prompt")
        }

        override suspend fun notify(event: AuthEvent) {
            if (event is AuthEvent.AuthUrl) authUrl = event.url
        }
    }

    private fun callbackInput(state: String, clientId: String? = "oaiapp_issued"): String {
        val params = mutableMapOf("code" to "authorization-code", "state" to state)
        if (clientId != null) params["client_id"] = clientId
        return OpenAiChatGPTOAuth.REDIRECT_URI + "?" + params.entries.joinToString("&") {
            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    private fun flow(http: OAuthHttpClient, callbackPort: Int = 0) = OpenAiChatGPTOAuth(
        http = http,
        clock = FakeClock(1_000_000L),
        pkce = PkceGenerator { "unit-test-verifier-unit-test-verifier".toByteArray() },
        callbackPort = callbackPort
    )

    private fun formFields(request: OAuthHttpRequest): Map<String, String> =
        formQuery(request.body.toString(Charsets.UTF_8))

    @Test
    fun `registers a user-owned client and stores its issued ID and granted scopes`() =
        runBlocking {
            val http = FakeHttpClient { tokenResponse() }
            lateinit var authUrl: String
            val auth = flow(http)
            val interaction = object : AuthInteraction {
                override suspend fun prompt(prompt: AuthPrompt): String =
                    callbackInput(formQuery(authUrl.substringAfter("?"))["state"]!!)

                override suspend fun notify(event: AuthEvent) {
                    if (event is AuthEvent.AuthUrl) authUrl = event.url
                }
            }

            val credential = auth.login(
                interaction,
                LoginOptions(getDeviceId = { deviceId })
            )

            val params = formQuery(authUrl.substringAfter("?"))
            assertEquals(OpenAiChatGPTOAuth.DYNAMIC_CLIENT_ID, params["client_id"])
            assertEquals("Pathfinder", params["agent_name_hint"])
            assertEquals("urn:uuid:$deviceId", params["ext_agent_host_id"])
            assertEquals(requiredScope, params["scope"])
            assertEquals(OpenAiChatGPTOAuth.REDIRECT_URI, params["redirect_uri"])
            assertEquals(OpenAiChatGPTOAuth.RESOURCE, params["resource"])
            assertEquals("S256", params["code_challenge_method"])

            val exchange = formFields(http.requests.single())
            assertEquals("oaiapp_issued", exchange["client_id"])
            assertEquals("authorization-code", exchange["code"])
            assertEquals(OpenAiChatGPTOAuth.RESOURCE, exchange["resource"])
            assertTrue(exchange["code_verifier"]!!.isNotEmpty())

            assertEquals("access-token", credential.access)
            assertEquals("refresh-token", credential.refresh)
            assertEquals(
                JsonPrimitive("oaiapp_issued"),
                credential.extras["clientId"]
            )
            assertEquals(
                JsonArray(requiredScope.split(" ").map { JsonPrimitive(it) }),
                credential.extras["scopes"]
            )
            assertEquals(1_000_000L + 3_600_000L - 180_000L, credential.expires)
        }

    @Test
    fun `uses the app agent name as the name hint`() = runBlocking {
        var state = ""
        val http = FakeHttpClient { tokenResponse() }
        val auth = flow(http)
        val interaction = object : AuthInteraction {
            var authUrl: String? = null

            override suspend fun prompt(prompt: AuthPrompt): String {
                val params = formQuery(authUrl!!.substringAfter("?"))
                state = params["state"]!!
                return callbackInput(state)
            }

            override suspend fun notify(event: AuthEvent) {
                if (event is AuthEvent.AuthUrl) authUrl = event.url
            }
        }

        auth.login(
            interaction,
            LoginOptions(getDeviceId = { deviceId }, agentName = "my-app")
        )

        val params = formQuery(interaction.authUrl!!.substringAfter("?"))
        assertEquals("my-app", params["agent_name_hint"])
    }

    @Test
    fun `rejects registration without an issued client ID`() = runBlocking {
        val http = FakeHttpClient { tokenResponse() }
        lateinit var authUrl: String
        val auth = flow(http)
        val interaction = object : AuthInteraction {
            override suspend fun prompt(prompt: AuthPrompt): String =
                callbackInput(formQuery(authUrl.substringAfter("?"))["state"]!!, clientId = null)

            override suspend fun notify(event: AuthEvent) {
                if (event is AuthEvent.AuthUrl) authUrl = event.url
            }
        }

        val error = assertFailsWith<IllegalStateException> {
            auth.login(interaction, LoginOptions(getDeviceId = { deviceId }))
        }
        assertTrue(
            error.message!!.contains("registration callback did not contain an issued client ID"),
            error.message
        )
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `rejects a token response that did not grant direct token use`() = runBlocking {
        val http = FakeHttpClient {
            tokenResponse("openid profile email offline_access resource.invoke")
        }
        lateinit var authUrl: String
        val auth = flow(http)
        val interaction = object : AuthInteraction {
            override suspend fun prompt(prompt: AuthPrompt): String =
                callbackInput(formQuery(authUrl.substringAfter("?"))["state"]!!)

            override suspend fun notify(event: AuthEvent) {
                if (event is AuthEvent.AuthUrl) authUrl = event.url
            }
        }

        val error = assertFailsWith<IllegalStateException> {
            auth.login(interaction, LoginOptions(getDeviceId = { deviceId }))
        }
        assertTrue(
            error.message!!.contains("grant did not include chatgpt.tokens.use.direct"),
            error.message
        )
    }

    @Test
    fun `requires a device ID before starting authorization`() = runBlocking {
        val http = FakeHttpClient { tokenResponse() }
        val auth = flow(http)
        val interaction = Interaction { error("should not prompt") }

        val missing = assertFailsWith<IllegalStateException> {
            auth.login(interaction)
        }
        assertTrue(missing.message!!.contains("requires a device ID"), missing.message)
        val invalid = assertFailsWith<IllegalStateException> {
            auth.login(interaction, LoginOptions(getDeviceId = { "not-a-uuid" }))
        }
        assertTrue(invalid.message!!.contains("requires a device ID"), invalid.message)
        assertTrue(http.requests.isEmpty())
        assertEquals(null, interaction.authUrl)
    }

    @Test
    fun `rejects a blank ID token`() = runBlocking {
        val http = FakeHttpClient {
            OAuthHttpResponse(
                200,
                emptyMap(),
                (
                    "{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":3600," +
                        "\"id_token\":\"   \",\"scope\":\"$requiredScope\"}"
                    ).toByteArray()
            )
        }
        lateinit var authUrl: String
        val auth = flow(http)
        val interaction = object : AuthInteraction {
            override suspend fun prompt(prompt: AuthPrompt): String =
                callbackInput(formQuery(authUrl.substringAfter("?"))["state"]!!)

            override suspend fun notify(event: AuthEvent) {
                if (event is AuthEvent.AuthUrl) authUrl = event.url
            }
        }

        val error = assertFailsWith<IllegalStateException> {
            auth.login(interaction, LoginOptions(getDeviceId = { deviceId }))
        }
        assertTrue(
            error.message!!.contains("did not contain an ID token"),
            error.message
        )
    }

    @Test
    fun `caller abort during login fails with Login cancelled`() = runBlocking {
        val http = FakeHttpClient { tokenResponse() }
        val auth = flow(http)
        val interaction = Interaction {
            throw kotlinx.coroutines.CancellationException("cancelled")
        }

        val error = assertFailsWith<IllegalStateException> {
            auth.login(interaction, LoginOptions(getDeviceId = { deviceId }))
        }
        assertEquals("Login cancelled", error.message)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `requires refresh responses to rotate the refresh token`() = runBlocking {
        val http = FakeHttpClient {
            OAuthHttpResponse(
                200,
                emptyMap(),
                (
                    "{\"access_token\":\"a\",\"expires_in\":3600,\"id_token\":\"i\"," +
                        "\"scope\":\"$requiredScope\"}"
                    ).toByteArray()
            )
        }
        val auth = flow(http)
        val error = assertFailsWith<IllegalStateException> {
            auth.refresh(connectedCredential())
        }
        assertTrue(
            error.message!!.contains("token response has invalid refresh_token"),
            error.message
        )
    }

    @Test
    fun `refreshes with the credential issued client ID and stores replacement scopes`() =
        runBlocking {
            val http = FakeHttpClient {
                tokenResponse(access = "new-access", refresh = "new-refresh")
            }
            val credential = flow(http).refresh(connectedCredential())

            val refresh = formFields(http.requests.single())
            assertEquals("refresh_token", refresh["grant_type"])
            assertEquals("oaiapp_existing", refresh["client_id"])
            assertEquals("old-refresh", refresh["refresh_token"])
            assertEquals(OpenAiChatGPTOAuth.RESOURCE, refresh["resource"])
            assertTrue("scope" !in refresh)
            assertEquals("new-access", credential.access)
            assertEquals("new-refresh", credential.refresh)
            assertEquals(
                JsonPrimitive("oaiapp_existing"),
                credential.extras["clientId"]
            )
            assertEquals(
                JsonArray(requiredScope.split(" ").map { JsonPrimitive(it) }),
                credential.extras["scopes"]
            )
        }

    @Test
    fun `fails before opening the browser when the callback port is taken`() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { blocker ->
            val http = FakeHttpClient { tokenResponse() }
            val auth = flow(http, callbackPort = blocker.localPort)
            val interaction = Interaction { error("should not prompt") }

            val error = assertFailsWith<IllegalStateException> {
                runBlocking {
                    auth.login(interaction, LoginOptions(getDeviceId = { deviceId }))
                }
            }
            assertTrue(error.message!!.contains("is in use"), error.message)
            assertEquals(null, interaction.authUrl)
            assertTrue(http.requests.isEmpty())
        }
    }

    private fun connectedCredential() = OAuthCredential(
        access = "old-access",
        refresh = "old-refresh",
        expires = 0,
        extras = mapOf(
            "clientId" to JsonPrimitive("oaiapp_existing"),
            "scopes" to JsonArray(requiredScope.split(" ").map { JsonPrimitive(it) })
        )
    )
}
