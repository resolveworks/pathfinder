package works.resolve.pathfinder.ai.testing

import works.resolve.pathfinder.ai.auth.ApiKeyAuth
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.AuthCheck
import works.resolve.pathfinder.ai.auth.AuthContext
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthResult
import works.resolve.pathfinder.ai.auth.ModelAuth
import works.resolve.pathfinder.ai.auth.OAuthAuth
import works.resolve.pathfinder.ai.auth.ProviderAuth

/** Minimal provider auth for tests that only need a key resolver. */
fun testProviderAuth(apiKey: ApiKeyAuth? = null, oauth: OAuthAuth? = null): ProviderAuth =
    object : ProviderAuth {
        override val apiKey: ApiKeyAuth? = apiKey
        override val oauth: OAuthAuth? = oauth
    }

fun testApiKeyAuth(
    name: String = "Test API key",
    login: (suspend (AuthInteraction) -> ApiKeyCredential)? = null,
    check: (suspend (AuthContext, ApiKeyCredential?) -> AuthCheck?)? = null,
    resolve: suspend (AuthContext, ApiKeyCredential?) -> AuthResult?
): ApiKeyAuth = object : ApiKeyAuth {
    override val name: String = name
    override val login = login
    override val check = check

    override suspend fun resolve(ctx: AuthContext, credential: ApiKeyCredential?): AuthResult? =
        resolve(ctx, credential)
}

/** Auth that always resolves to [apiKey], regardless of the stored credential. */
fun fixedApiKeyAuth(
    apiKey: String,
    env: Map<String, String> = emptyMap(),
    source: String? = "test"
): ProviderAuth = testProviderAuth(
    apiKey = testApiKeyAuth { _, _ ->
        AuthResult(auth = ModelAuth(apiKey = apiKey), env = env, source = source)
    }
)

/** Auth that resolves the stored/explicit key, optionally falling back to [fallback]. */
fun credentialApiKeyAuth(fallback: String? = null): ProviderAuth = testProviderAuth(
    apiKey = testApiKeyAuth { _, credential ->
        val key = credential?.key ?: fallback ?: return@testApiKeyAuth null
        AuthResult(
            auth = ModelAuth(apiKey = key),
            env = credential?.env.orEmpty(),
            source = if (credential != null) "stored credential" else "test"
        )
    }
)
