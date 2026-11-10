package tw.personal.cwaweather.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException

/** Records the oldest cached response used while loading one screen of data. */
class OfflineTracker {
    private val oldest = AtomicReference<Long?>(null)

    val offlineSince: Long? get() = oldest.get()

    fun record(savedAt: Long) {
        oldest.updateAndGet { current -> if (current == null) savedAt else minOf(current, savedAt) }
    }
}

/**
 * Saves each raw API response and falls back to the saved copy when the service cannot be reached.
 * Authorization failures are not hidden by old data, so a bad key still surfaces as an error.
 * A saved copy older than [maxAgeMillis] is not used, so stale weather is never shown as current.
 */
class CachedSource(
    private val api: CwaApi,
    private val directory: File,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Deletes saved responses that are too old to be used, so the cache does not grow forever. */
    fun prune() {
        val cutoff = clock() - maxAgeMillis
        directory.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    suspend fun fetch(
        dataset: String,
        query: Map<String, String> = emptyMap(),
        tracker: OfflineTracker,
    ): JsonObject {
        val file = fileFor(dataset, query)
        val response = try {
            api.datastore(dataset, query)
        } catch (e: IOException) {
            return readSaved(file, tracker) ?: throw e
        } catch (e: HttpException) {
            if (e.code() < 500) throw e
            return readSaved(file, tracker) ?: throw e
        }
        // A failed write must not turn a good response into an error.
        runCatching { save(file, response) }
        return response
    }

    private fun readSaved(file: File, tracker: OfflineTracker): JsonObject? {
        if (!file.exists() || clock() - file.lastModified() > maxAgeMillis) return null
        val saved = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return null
        tracker.record(file.lastModified())
        return saved
    }

    private fun save(file: File, response: JsonObject) {
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.tmp")
        temporary.writeText(response.toString())
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("Could not replace ${file.name}")
        }
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000
    }

    private fun fileFor(dataset: String, query: Map<String, String>): File {
        val identity = dataset + query.toSortedMap().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
}
