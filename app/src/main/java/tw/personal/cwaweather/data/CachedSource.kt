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
 */
class CachedSource(private val api: CwaApi, private val directory: File) {
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
        save(file, response)
        return response
    }

    private fun readSaved(file: File, tracker: OfflineTracker): JsonObject? {
        if (!file.exists()) return null
        val saved = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
            ?: return null
        tracker.record(file.lastModified())
        return saved
    }

    private fun save(file: File, response: JsonObject) {
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.tmp")
        temporary.writeText(response.toString())
        temporary.renameTo(file)
    }

    private fun fileFor(dataset: String, query: Map<String, String>): File {
        val identity = dataset + query.toSortedMap().toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$hash.json")
    }
}
