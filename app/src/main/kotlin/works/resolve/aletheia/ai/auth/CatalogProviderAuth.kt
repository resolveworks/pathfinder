package works.resolve.aletheia.ai.auth

import works.resolve.aletheia.ai.providers.CatalogProvider

/**
 * Bridge from the generated provider catalog to the ported auth contracts
 * (pi's provider auth shapes, e.g. `providers/cloudflare-auth.ts` and
 * `auth/helpers.ts` `envApiKeyAuth`): a catalog entry becomes a
 * [ProviderAuth] whose [ApiKeyAuth] resolves from a stored credential and the
 * ambient [AuthContext] with per-field merge (credential value first,
 * `ctx.env(envKey)` second), reusing the catalog's own completeness and
 * shaping rules instead of duplicating provider models.
 *
 * Catalog prompts map to pi's per-field merge exactly: the first prompt fills
 * the API key, every later prompt its env slot. A credential is configured
 * only when every prompt resolves nonblank (pi's Cloudflare resolution
 * returns unconfigured unless every value exists); following the catalog's
 * blank-is-missing rule, blank values fall through to ambient instead of
 * short-circuiting like pi's `!== undefined` check. OAuth-only prompt-less
 * providers carry no API-key handler at all: pi leaves `ProviderAuth.apiKey`
 * unset for them (pi's `openai-codex.ts` has no `apiKey` auth), so their auth
 * composition depends entirely on a registered OAuth flow.
 */
class CatalogApiKeyAuth(private val entry: CatalogProvider) : ApiKeyAuth {
    /** The catalog label verbatim (pi's `envApiKeyAuth` name); fallback for
     * label-less entries, matching the old `"Anthropic API key"`-style labels. */
    override val name: String = entry.auth.label ?: "${entry.name} API key"

    override suspend fun resolve(ctx: AuthContext, credential: ApiKeyCredential?): AuthResult? {
        val prompts = entry.auth.prompts
        val env = credential?.env?.toMutableMap() ?: mutableMapOf()
        var apiKey: String? = null
        prompts.forEachIndexed { index, prompt ->
            val stored = if (index == 0) credential?.key else credential?.env?.get(prompt.envKey)
            val value =
                stored?.takeIf { it.isNotBlank() } ?: ctx.env(prompt.envKey)?.takeIf { it.isNotBlank() }
            if (value == null) return null
            if (index == 0) apiKey = value else env[prompt.envKey] = value
        }
        val key = apiKey ?: return null
        val source = if (credential != null) "stored credential" else prompts.first().envKey

        return AuthResult(
            auth = entry.toModelAuth(key, env),
            env = env,
            source = source,
        )
    }
}

/**
 * Composition point mapping catalog OAuth-capable providers to concrete
 * [OAuthAuth] implementations (pi wires flows directly in provider
 * definitions; Android needs late composition because flows are ports that
 * do not exist yet). Returns null for a provider without a registered flow:
 * the provider's [ProviderAuth.oauth] is then absent and a stored OAuth
 * credential resolves as unconfigured, exactly like pi's handler-less
 * credential.
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

/** Registry backed by an explicit provider-id map (tests, later app wiring). */
class MapCatalogAuthRegistry(private val oauthById: Map<String, OAuthAuth>) : CatalogAuthRegistry {
    override fun oauthAuth(provider: CatalogProvider): OAuthAuth? = oauthById[provider.id]
}

/** A catalog entry as pi's `{ id, auth }` provider reference. */
class CatalogAuthProviderRef(
    entry: CatalogProvider,
    registry: CatalogAuthRegistry = CatalogAuthRegistry.EMPTY,
) : AuthProviderRef {
    override val id: String = entry.id
    override val auth: ProviderAuth = CatalogProviderAuth(entry, registry)
}

/** Catalog entry → [ProviderAuth] composition (pi's `ProviderAuth`): an
 * API-key handler exists iff the catalog carries key prompts — OAuth-only
 * prompt-less providers (pi's `openai-codex`) have none. */
class CatalogProviderAuth(
    entry: CatalogProvider,
    registry: CatalogAuthRegistry = CatalogAuthRegistry.EMPTY,
) : ProviderAuth {
    override val apiKey: ApiKeyAuth? = entry.auth.prompts.takeIf { it.isNotEmpty() }?.let { CatalogApiKeyAuth(entry) }
    override val oauth: OAuthAuth? = registry.oauthAuth(entry)
}

/**
 * [AuthContext] for the app boundary: Android has no ambient provider env
 * and no filesystem credential files (credentials live behind
 * [CredentialStore]), so every ambient lookup reports absent. Injectable so
 * tests (and future embedders) can supply real ambient sources.
 */
object NoopAuthContext : AuthContext {
    override suspend fun env(name: String): String? = null
    override suspend fun fileExists(path: String): Boolean = false
}
