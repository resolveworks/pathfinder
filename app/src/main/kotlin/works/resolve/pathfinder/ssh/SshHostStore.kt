package works.resolve.pathfinder.ssh

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persistent store of [SshHost] configs plus the per-host trusted host-key
 * fingerprint (TOFU state). Private keys live in [SshHostKeyStore], not here.
 *
 * Hosts are stored as one preference group per host id; [hosts] regroups
 * them on every emission for the settings UI. The id is opaque to users:
 * 32 lowercase hex chars, generated once at creation.
 */
class SshHostStore(
    private val dataStore: DataStore<Preferences>,
    private val keyStore: SshHostKeyStore
) {

    val hosts: Flow<List<SshHost>> = dataStore.data.map { prefs -> decodeHosts(prefs) }

    suspend fun host(id: String): SshHost? = decodeHosts(dataStore.data.first()).firstOrNull {
        it.id ==
            id
    }

    /**
     * Creates a host with a freshly generated per-host keypair. The keypair
     * is persisted before the config becomes visible, so a listed host always
     * has a usable key.
     */
    suspend fun addHost(address: String, port: Int, username: String, cwd: String): SshHost {
        val id = UUID.randomUUID().toString().replace("-", "")
        val keypair = SshHostKeys.generate()
        keyStore.write(id, keypair.privateKey)
        val host =
            SshHost(
                id = id,
                address = address,
                port = port,
                username = username,
                cwd = cwd,
                publicKeyLine = keypair.publicKeyLine
            )
        dataStore.edit { prefs -> writeHost(prefs, host) }
        return host
    }

    suspend fun updateHost(host: SshHost) {
        dataStore.edit { prefs -> writeHost(prefs, host) }
    }

    suspend fun removeHost(id: String) {
        dataStore.edit { prefs ->
            prefs.remove(addressKey(id))
            prefs.remove(portKey(id))
            prefs.remove(usernameKey(id))
            prefs.remove(cwdKey(id))
            prefs.remove(publicKeyKey(id))
            prefs.remove(trustedFingerprintKey(id))
        }
        keyStore.delete(id)
    }

    suspend fun privateKey(id: String): SshPrivateKeyPem? = keyStore.read(id)

    suspend fun trustedHostKeyFingerprint(id: String): String? =
        dataStore.data.first()[trustedFingerprintKey(id)]

    suspend fun trustHostKeyFingerprint(id: String, fingerprint: String) {
        dataStore.edit { it[trustedFingerprintKey(id)] = fingerprint }
    }

    private fun decodeHosts(prefs: Preferences): List<SshHost> {
        val hosts = mutableListOf<SshHost>()
        for (key in prefs.asMap().keys) {
            if (!key.name.startsWith(PREFIX) || !key.name.endsWith(SUFFIX_ADDRESS)) continue
            val id = key.name.removePrefix(PREFIX).removeSuffix(SUFFIX_ADDRESS)
            val address = prefs[key] as? String ?: continue
            val username = prefs[usernameKey(id)] ?: continue
            val cwd = prefs[cwdKey(id)] ?: continue
            val port = prefs[portKey(id)] ?: DEFAULT_PORT
            val publicKeyLine = prefs[publicKeyKey(id)] ?: continue
            hosts += SshHost(id, address, port, username, cwd, publicKeyLine)
        }
        return hosts.sortedBy { it.id }
    }

    private fun writeHost(prefs: MutablePreferences, host: SshHost) {
        prefs[addressKey(host.id)] = host.address
        prefs[portKey(host.id)] = host.port
        prefs[usernameKey(host.id)] = host.username
        prefs[cwdKey(host.id)] = host.cwd
        prefs[publicKeyKey(host.id)] = host.publicKeyLine
    }

    private fun addressKey(id: String) = stringPreferencesKey("${PREFIX}$id$SUFFIX_ADDRESS")
    private fun portKey(id: String) = intPreferencesKey("${PREFIX}$id$SUFFIX_PORT")
    private fun usernameKey(id: String) = stringPreferencesKey("${PREFIX}$id$SUFFIX_USERNAME")
    private fun cwdKey(id: String) = stringPreferencesKey("${PREFIX}$id$SUFFIX_CWD")
    private fun publicKeyKey(id: String) = stringPreferencesKey("${PREFIX}$id$SUFFIX_PUBLIC_KEY")
    private fun trustedFingerprintKey(id: String) =
        stringPreferencesKey("${PREFIX}$id$SUFFIX_TRUSTED_FINGERPRINT")

    private companion object {
        const val PREFIX = "host."
        const val SUFFIX_ADDRESS = ".address"
        const val SUFFIX_PORT = ".port"
        const val SUFFIX_USERNAME = ".username"
        const val SUFFIX_CWD = ".cwd"
        const val SUFFIX_PUBLIC_KEY = ".public_key"
        const val SUFFIX_TRUSTED_FINGERPRINT = ".trusted_fingerprint"
        const val DEFAULT_PORT = 22
    }
}
