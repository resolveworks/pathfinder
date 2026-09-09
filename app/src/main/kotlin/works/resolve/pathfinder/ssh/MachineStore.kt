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
 * Persistent store of [Machine] configs plus the per-machine trusted
 * host-key fingerprint (TOFU state). Private keys live in [MachineKeyStore],
 * not here.
 *
 * Machines are stored as one preference group per machine id; [machines]
 * regroups them on every emission for the settings UI. The id is opaque to
 * users: 32 lowercase hex chars, generated once at creation.
 */
class MachineStore(
    private val dataStore: DataStore<Preferences>,
    private val keyStore: MachineKeyStore
) {

    val machines: Flow<List<Machine>> = dataStore.data.map { prefs -> decodeMachines(prefs) }

    suspend fun machine(id: String): Machine? = decodeMachines(dataStore.data.first()).firstOrNull {
        it.id ==
            id
    }

    /**
     * Creates a machine with a freshly generated per-machine keypair. The
     * keypair is persisted before the config becomes visible, so a listed
     * machine always has a usable key.
     */
    suspend fun addMachine(address: String, port: Int, username: String, cwd: String): Machine {
        val id = UUID.randomUUID().toString().replace("-", "")
        val keypair = MachineKeys.generate()
        keyStore.write(id, keypair.privateKey)
        val machine =
            Machine(
                id = id,
                address = address,
                port = port,
                username = username,
                cwd = cwd,
                publicKeyLine = keypair.publicKeyLine
            )
        dataStore.edit { prefs -> writeMachine(prefs, machine) }
        return machine
    }

    suspend fun updateMachine(machine: Machine) {
        dataStore.edit { prefs -> writeMachine(prefs, machine) }
    }

    suspend fun removeMachine(id: String) {
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

    private fun decodeMachines(prefs: Preferences): List<Machine> {
        val machines = mutableListOf<Machine>()
        for (key in prefs.asMap().keys) {
            if (!key.name.startsWith(PREFIX) || !key.name.endsWith(SUFFIX_ADDRESS)) continue
            val id = key.name.removePrefix(PREFIX).removeSuffix(SUFFIX_ADDRESS)
            val address = prefs[key] as? String ?: continue
            val username = prefs[usernameKey(id)] ?: continue
            val cwd = prefs[cwdKey(id)] ?: continue
            val port = prefs[portKey(id)] ?: DEFAULT_PORT
            val publicKeyLine = prefs[publicKeyKey(id)] ?: continue
            machines += Machine(id, address, port, username, cwd, publicKeyLine)
        }
        return machines.sortedBy { it.id }
    }

    private fun writeMachine(prefs: MutablePreferences, machine: Machine) {
        prefs[addressKey(machine.id)] = machine.address
        prefs[portKey(machine.id)] = machine.port
        prefs[usernameKey(machine.id)] = machine.username
        prefs[cwdKey(machine.id)] = machine.cwd
        prefs[publicKeyKey(machine.id)] = machine.publicKeyLine
    }

    private fun addressKey(id: String) = stringPreferencesKey("${PREFIX}$id$SUFFIX_ADDRESS")
    private fun portKey(id: String) = intPreferencesKey("${PREFIX}$id$SUFFIX_PORT")
    private fun usernameKey(id: String) = stringPreferencesKey("${PREFIX}$id$SUFFIX_USERNAME")
    private fun cwdKey(id: String) = stringPreferencesKey("${PREFIX}$id$SUFFIX_CWD")
    private fun publicKeyKey(id: String) = stringPreferencesKey("${PREFIX}$id$SUFFIX_PUBLIC_KEY")
    private fun trustedFingerprintKey(id: String) =
        stringPreferencesKey("${PREFIX}$id$SUFFIX_TRUSTED_FINGERPRINT")

    private companion object {
        const val PREFIX = "machine."
        const val SUFFIX_ADDRESS = ".address"
        const val SUFFIX_PORT = ".port"
        const val SUFFIX_USERNAME = ".username"
        const val SUFFIX_CWD = ".cwd"
        const val SUFFIX_PUBLIC_KEY = ".public_key"
        const val SUFFIX_TRUSTED_FINGERPRINT = ".trusted_fingerprint"
        const val DEFAULT_PORT = 22
    }
}
