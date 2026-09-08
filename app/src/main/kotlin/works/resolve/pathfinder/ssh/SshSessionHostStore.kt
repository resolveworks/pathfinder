package works.resolve.pathfinder.ssh

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * App-side mapping of chat session id → SSH host id, stored in app metadata:
 * the session JSONL keeps pi's wire format and carries no host information.
 * Assigning hosts to sessions (session-creation UI) is a later phase; this
 * store exists so the agent factory can resolve a session's host.
 */
class SshSessionHostStore(private val dataStore: DataStore<Preferences>) {

    suspend fun hostId(sessionId: String): String? = dataStore.data.first()[hostKey(sessionId)]

    suspend fun setHost(sessionId: String, hostId: String) {
        dataStore.edit { it[hostKey(sessionId)] = hostId }
    }

    suspend fun clear(sessionId: String) {
        dataStore.edit { it.remove(hostKey(sessionId)) }
    }

    /** Drops every mapping pointing at [hostId] (host deletion). */
    suspend fun clearHost(hostId: String) {
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { key ->
                    key.name.startsWith(PREFIX) && key.name.endsWith(SUFFIX) && prefs[key] == hostId
                }
                .forEach { prefs.remove(it) }
        }
    }

    private fun hostKey(sessionId: String) = stringPreferencesKey("$PREFIX$sessionId$SUFFIX")

    private companion object {
        const val PREFIX = "session."
        const val SUFFIX = ".host_id"
    }
}
