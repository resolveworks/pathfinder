package works.resolve.pathfinder.data.sessions

import ai.koog.prompt.message.Message
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * JSON codec for [Session] files. Format: one file per session containing
 * only transcript data — never API keys or provider catalogs. Message
 * payloads are Koog [Message]s persisted with kotlinx serialization
 * (`ai.koog.prompt.message.Message`, `@Serializable`). Unknown, malformed, or
 * old-format data is rejected with a [SessionDataException]; nothing is
 * converted and nothing is silently dropped.
 */
internal object SessionCodec {

    private const val FORMAT_VERSION = 3

    private val json: Json = Json {
        ignoreUnknownKeys = false
        classDiscriminator = "type" // Koog Message's own discriminator
    }

    fun encode(session: Session): String = buildJsonObject {
        put("format", FORMAT_VERSION)
        put("id", session.id)
        put("title", session.title)
        put("createdAt", session.createdAt)
        put("updatedAt", session.updatedAt)
        put("entries", JsonArray(session.entries.map(::encodeEntry)))
        put("leafId", session.leafId)
    }.toString()

    fun decode(text: String): Session {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw SessionDataException("Malformed session data", e)
        }
        val obj = element as? JsonObject
            ?: throw SessionDataException("Malformed session data: expected object")
        val version = (obj["format"] as? JsonPrimitive)?.longOrNull
            ?: throw SessionDataException("Unsupported session format")
        if (version != FORMAT_VERSION.toLong()) {
            throw SessionDataException("Unsupported session format: $version")
        }
        return decodeV3(obj)
    }

    private fun decodeV3(obj: JsonObject): Session {
        val entryArray = (obj["entries"] as? JsonArray)
            ?: throw SessionDataException("Malformed session data: missing entries")
        val entries = entryArray.map(::decodeEntry)
        validateGraph(entries)
        val leafId = obj.stringField("leafId")
        if (leafId != null && entries.none { it.id == leafId }) {
            throw SessionDataException("Malformed session data: leafId not in entries: $leafId")
        }
        return Session(
            id = requireId(obj.stringField("id") ?: throw SessionDataException("Malformed session data: missing id")),
            title = obj.stringField("title") ?: throw SessionDataException("Malformed session data: missing title"),
            createdAt = obj.longField("createdAt")
                ?: throw SessionDataException("Malformed session data: missing createdAt"),
            updatedAt = obj.longField("updatedAt")
                ?: throw SessionDataException("Malformed session data: missing updatedAt"),
            entries = entries,
            leafId = leafId,
        )
    }

    /**
     * Graph invariants enforced at the decode boundary: unique entry ids and
     * parent references that exist and never loop (self-parents included).
     * With these guaranteed, [Conversation] never needs runtime cycle/orphan
     * guards. A null `leafId` stays legal (brand-new/empty session).
     */
    private fun validateGraph(entries: List<SessionEntry>) {
        val byId = HashMap<String, SessionEntry>(entries.size)
        for (entry in entries) {
            if (byId.put(entry.id, entry) != null) {
                throw SessionDataException("Malformed session data: duplicate entry id: ${entry.id}")
            }
        }
        for (entry in entries) {
            val pid = entry.parentId ?: continue
            if (pid == entry.id) {
                throw SessionDataException("Malformed session data: entry ${entry.id} parents itself")
            }
            if (pid !in byId) {
                throw SessionDataException("Malformed session data: entry ${entry.id} references unknown parentId: $pid")
            }
        }
        // Parent chains must terminate at a root. `resolved` marks entries
        // already proven acyclic so the whole pass stays linear.
        val resolved = HashSet<String>()
        for (entry in entries) {
            val onPath = LinkedHashSet<String>()
            var current: SessionEntry? = entry
            while (current != null && current.id !in resolved) {
                if (!onPath.add(current.id)) {
                    throw SessionDataException("Malformed session data: cycle in parent chain at entry ${current.id}")
                }
                current = current.parentId?.let(byId::get)
            }
            resolved += onPath
        }
    }

    // ---- Entries ----

    private fun encodeEntry(entry: SessionEntry): JsonObject = when (entry) {
        is MessageEntry -> buildJsonObject {
            put("kind", "message")
            put("id", entry.id)
            entry.parentId?.let { put("parentId", it) }
            put("timestamp", entry.timestamp)
            put("message", json.encodeToJsonElement(Message.serializer(), entry.message))
        }
    }

    private fun decodeEntry(element: kotlinx.serialization.json.JsonElement): SessionEntry {
        val obj = element as? JsonObject
            ?: throw SessionDataException("Malformed session data: entry must be an object")
        val id = obj.stringField("id") ?: throw SessionDataException("Malformed session data: entry missing id")
        return when (val kind = obj.stringField("kind")) {
            "message" -> {
                val messageElement = obj["message"]
                    ?: throw SessionDataException("Malformed session data: entry missing message")
                MessageEntry(
                    id = id,
                    parentId = obj.stringField("parentId"),
                    timestamp = obj.longField("timestamp")
                        ?: throw SessionDataException("Malformed session data: missing message timestamp"),
                    message = try {
                        json.decodeFromJsonElement(Message.serializer(), messageElement)
                    } catch (e: Exception) {
                        throw SessionDataException("Malformed session data: undecodable message", e)
                    },
                )
            }

            else -> throw SessionDataException("Unknown entry kind: $kind")
        }
    }

    /** Strict string field; numbers/booleans/null yield null. */
    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Strict long field; string-encoded numbers yield null. */
    private fun JsonObject.longField(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
