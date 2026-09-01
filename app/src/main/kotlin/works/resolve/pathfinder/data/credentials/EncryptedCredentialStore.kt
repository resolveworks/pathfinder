package works.resolve.pathfinder.data.credentials

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.CredentialInfo
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_CONTEXT
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.TelemetryContext
import works.resolve.pathfinder.telemetry.TelemetryError
import works.resolve.pathfinder.telemetry.attr

/**
 * Persistent [CredentialStore] (pi contract from
 * `packages/ai/src/auth/types.ts`): stores one credential per provider as
 * AES-GCM ciphertext (via [KeystoreAeadCipher], backed by the Android
 * Keystore) in per-provider files under the app's private storage, serialized
 * with [CredentialCodec] (type-tagged JSON only).
 *
 * Writes are serialized per provider with an in-process mutex — the app is a
 * single Android process, so pi's cross-process file-lock requirement
 * collapses to this. Key material never leaves the credential boundary in
 * plaintext and is never logged.
 *
 * Failures to read (decrypt), decode, or persist a credential are recorded as
 * sanitized telemetry spans (`pf.credentials.*`) before the original
 * exception is rethrown. Sanitization follows the discipline proven out on
 * the abandoned diagnostics work: only the provider id, operation outcome,
 * and exception *type* are recorded — never exception messages, which can
 * embed platform detail, and never file content.
 */
class EncryptedCredentialStore(
    private val dir: File,
    private val encrypt: (ByteArray) -> ByteArray,
    private val decrypt: (ByteArray) -> ByteArray,
    private val telemetryContext: TelemetryContext = NOOP_TELEMETRY_CONTEXT,
) : CredentialStore {

    constructor(
        context: Context,
        cipher: KeystoreAeadCipher,
        telemetryContext: TelemetryContext = NOOP_TELEMETRY_CONTEXT,
    ) : this(
        dir = File(context.filesDir, DIRECTORY),
        encrypt = cipher::encrypt,
        decrypt = cipher::decrypt,
        telemetryContext = telemetryContext,
    )

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(providerId: String): Mutex = locks.computeIfAbsent(providerId) { Mutex() }

    private fun fileFor(providerId: String): File {
        require(PROVIDER_ID_REGEX.matches(providerId)) { "Invalid provider id" }
        return File(dir, "$providerId.bin")
    }

    private suspend fun readRaw(providerId: String): String? =
        telemetryContext.startSpan(
            SpanOptions(
                name = SPAN_READ,
                attributes = mapOf(ATTR_PROVIDER to attr(providerId)),
            ),
        ) { span ->
            try {
                val raw = readRawSpanned(providerId)
                span.setAttributes(mapOf(ATTR_OUTCOME to attr(if (raw == null) OUTCOME_ABSENT else OUTCOME_DECRYPTED)))
                raw
            } catch (error: CancellationException) {
                // Cancellation is not a failure. The span must be settled ok
                // explicitly: the contract's automatic status would otherwise
                // record the CancellationException as an error.
                span.setStatus(SpanStatus.Ok)
                throw error
            } catch (error: Throwable) {
                span.setStatus(typeOnlyError(error))
                throw error
            }
        }

    private suspend fun readRawSpanned(providerId: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(providerId)
        if (!file.exists()) return@withContext null
        String(decrypt(file.readBytes()), Charsets.UTF_8)
    }

    private suspend fun writeRaw(providerId: String, encoded: String) =
        telemetryContext.startSpan(
            SpanOptions(
                name = SPAN_WRITE,
                attributes = mapOf(ATTR_PROVIDER to attr(providerId)),
            ),
        ) { span ->
            try {
                writeRawSpanned(providerId, encoded)
                span.setAttributes(mapOf(ATTR_OUTCOME to attr(OUTCOME_PERSISTED)))
            } catch (error: CancellationException) {
                // Cancellation is not a failure; settle ok so the automatic
                // status does not record a CancellationException error.
                span.setStatus(SpanStatus.Ok)
                throw error
            } catch (error: Throwable) {
                span.setStatus(typeOnlyError(error))
                throw error
            }
        }

    private suspend fun writeRawSpanned(providerId: String, encoded: String) = withContext(Dispatchers.IO) {
        val file = fileFor(providerId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(encrypt(encoded.toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not persist credential" }
        }
    }

    private suspend fun decodeRaw(providerId: String): Credential? =
        readRaw(providerId)?.let { raw ->
            telemetryContext.startSpan(
                SpanOptions(
                    name = SPAN_DECODE,
                    attributes = mapOf(ATTR_PROVIDER to attr(providerId)),
                ),
            ) { span ->
                try {
                    CredentialCodec.decode(raw)
                } catch (error: CredentialFormatException) {
                    span.setStatus(typeOnlyError(error))
                    throw CredentialFormatException("Stored credential for $providerId is malformed: ${error.message}")
                }
            }
        }

    override suspend fun read(providerId: String): Credential? = lockFor(providerId).withLock {
        decodeRaw(providerId)
    }

    override suspend fun list(): List<CredentialInfo> {
        val names = withContext(Dispatchers.IO) {
            (dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) } ?: emptyArray())
                .map { it.name.removeSuffix(FILE_SUFFIX) }
        }
        val infos = mutableListOf<CredentialInfo>()
        for (providerId in names) {
            // Each entry is read under its provider lock so a same-provider
            // modify/delete cannot interleave, and storage/format failures
            // reject: configured credentials never silently disappear from
            // the listing. Only the non-secret type tag is surfaced.
            val credential = lockFor(providerId).withLock { decodeRaw(providerId) }
                ?: continue // deleted between snapshot and lock: a race, not a failure
            infos += CredentialInfo(providerId, credential.type)
        }
        return infos.sortedBy { it.providerId }
    }

    override suspend fun modify(
        providerId: String,
        update: suspend (current: Credential?) -> Credential?,
    ): Credential? = lockFor(providerId).withLock {
        val current = decodeRaw(providerId)
        val next = update(current)
        if (next != null) writeRaw(providerId, CredentialCodec.encode(next))
        next ?: current
    }

    override suspend fun delete(providerId: String): Unit = lockFor(providerId).withLock {
        telemetryContext.startSpan(
            SpanOptions(
                name = SPAN_DELETE,
                attributes = mapOf(ATTR_PROVIDER to attr(providerId)),
            ),
        ) { span ->
            val deleted = withContext(Dispatchers.IO) { fileFor(providerId).delete() }
            span.setAttributes(mapOf(ATTR_OUTCOME to attr(if (deleted) OUTCOME_DELETED else OUTCOME_ABSENT)))
        }
    }

    private companion object {
        /** App-owned span vocabulary (pi packages define `pi.*` schemas; Pathfinder's are `pf.*`). */
        const val SPAN_READ = "pf.credentials.read"
        const val SPAN_WRITE = "pf.credentials.write"
        const val SPAN_DECODE = "pf.credentials.decode"
        const val SPAN_DELETE = "pf.credentials.delete"
        const val ATTR_PROVIDER = "pf.credentials.provider"
        const val ATTR_OUTCOME = "pf.credentials.outcome"
        const val OUTCOME_DECRYPTED = "decrypted"
        const val OUTCOME_ABSENT = "absent"
        const val OUTCOME_PERSISTED = "persisted"
        const val OUTCOME_DELETED = "deleted"

        /** Exception messages can embed platform detail; record the type only. */
        fun typeOnlyError(error: Throwable): SpanStatus = SpanStatus.Error(
            TelemetryError(name = error::class.qualifiedName ?: error::class.simpleName ?: "unknown", message = ""),
        )

        const val DIRECTORY = "credentials"
        const val FILE_SUFFIX = ".bin"
        val PROVIDER_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
