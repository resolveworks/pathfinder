package works.resolve.pathfinder.ai.auth

import works.resolve.pathfinder.ai.providers.CatalogProvider

/**
 * Bridge from the generated provider catalog to the ported auth contracts,
 * following pi's per-field merge (e.g. `cloudflare-auth.ts`): the first
 * prompt fills the API key, every later prompt its env slot, and resolution
 * succeeds only when every field resolves. Divergence from pi: pi treats
 * `undefined` as missing and short-circuits; the catalog's blank-is-missing
 * rule makes blank values fall through to ambient instead.
 */
class CatalogApiKeyAuth(private val entry: CatalogProvider) : ApiKeyAuth {
    override val name: String = entry.auth.label ?: "${entry.name} API key"

    /** Entered values are never logged: failure text carries prompt metadata only. */
    override val login: (suspend (interaction: AuthInteraction) -> ApiKeyCredential)? =
        suspend { interaction ->
            var key: String? = null
            val env = mutableMapOf<String, String>()
            entry.auth.prompts.forEachIndexed { index, prompt ->
                val value = interaction.prompt(
                    if (prompt.secret) {
                        AuthPrompt.Secret(
                            prompt.message
                        )
                    } else {
                        AuthPrompt.Text(prompt.message)
                    }
                )
                if (value.isBlank()) {
                    throw ModelsError(
                        ModelsErrorCode.AUTH,
                        "${entry.name} requires a value for ${prompt.envKey}"
                    )
                }
                if (index == 0) key = value else env[prompt.envKey] = value
            }
            ApiKeyCredential(key = key, env = env)
        }

    override suspend fun resolve(ctx: AuthContext, credential: ApiKeyCredential?): AuthResult? {
        val prompts = entry.auth.prompts
        val env = credential?.env?.toMutableMap() ?: mutableMapOf()
        var apiKey: String? = null
        prompts.forEachIndexed { index, prompt ->
            val stored = if (index == 0) credential?.key else credential?.env?.get(prompt.envKey)
            val value =
                stored?.takeIf { it.isNotBlank() }
                    ?: ctx.env(prompt.envKey)?.takeIf { it.isNotBlank() }
            if (value == null) return null
            if (index == 0) apiKey = value else env[prompt.envKey] = value
        }
        val key = apiKey ?: return null
        val source = if (credential != null) "stored credential" else prompts.first().envKey

        return AuthResult(
            auth = entry.toModelAuth(key, env),
            env = env,
            source = source
        )
    }
}

/**
 * Maps OAuth-capable catalog providers to their [OAuthAuth] flow: pi wires
 * flows directly into provider definitions; here flows compose late at the
 * app boundary. A provider without a registered flow has no
 * [ProviderAuth.oauth], so a stored OAuth credential resolves as
 * unconfigured (pi's handler-less credential).
 */
interface CatalogAuthRegistry {
    fun oauthAuth(provider: CatalogProvider): OAuthAuth?

    companion object {
        /** No OAuth flows registered: every catalog provider is API-key-only. */
        val EMPTY: CatalogAuthRegistry = object : CatalogAuthRegistry {
            override fun oauthAuth(provider: CatalogProvider): OAuthAuth? = null
        }
    }
}

class MapCatalogAuthRegistry(private val oauthById: Map<String, OAuthAuth>) : CatalogAuthRegistry {
    override fun oauthAuth(provider: CatalogProvider): OAuthAuth? = oauthById[provider.id]
}

class CatalogAuthProviderRef(
    entry: CatalogProvider,
    registry: CatalogAuthRegistry = CatalogAuthRegistry.EMPTY
) : AuthProviderRef {
    override val id: String = entry.id
    override val auth: ProviderAuth = CatalogProviderAuth(entry, registry)
}

/** An API-key handler exists iff the catalog carries key prompts; OAuth-only
 * prompt-less providers (pi's `openai-codex`) have none. */
class CatalogProviderAuth(
    entry: CatalogProvider,
    registry: CatalogAuthRegistry = CatalogAuthRegistry.EMPTY
) : ProviderAuth {
    override val apiKey: ApiKeyAuth? = entry.auth.prompts.takeIf {
        it.isNotEmpty()
    }?.let { CatalogApiKeyAuth(entry) }
    override val oauth: OAuthAuth? = registry.oauthAuth(entry)
}

/**
 * [AuthContext] for the app boundary: Android has no ambient provider env and
 * no credential files (credentials live behind [CredentialStore]), so every
 * ambient lookup reports absent.
 */
object NoopAuthContext : AuthContext {
    override suspend fun env(name: String): String? = null
    override suspend fun fileExists(path: String): Boolean = false
}
