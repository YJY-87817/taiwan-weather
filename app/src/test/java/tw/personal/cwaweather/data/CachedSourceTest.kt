package tw.personal.cwaweather.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class CachedSourceTest {
    private val saved = response("saved")

    private fun response(value: String) =
        JsonObject(mapOf("records" to JsonObject(mapOf("value" to JsonPrimitive(value)))))

    /** Ten minutes ahead of the files' real timestamps, so a saved copy is no longer fresh. */
    private val later = { System.currentTimeMillis() + 10 * 60 * 1000 }
    private val directory: File = Files.createTempDirectory("api-cache").toFile()

    private class FakeApi(var result: () -> JsonObject) : CwaApi {
        override suspend fun datastore(dataset: String, query: Map<String, String>) = result()
    }

    private fun httpError(code: Int) =
        HttpException(Response.error<JsonObject>(code, "".toResponseBody(null)))

    private fun sourceWithSavedResponse(api: FakeApi): CachedSource {
        val source = CachedSource(api, directory, clock = later)
        runBlocking { source.fetch("A", mapOf("q" to "1"), OfflineTracker()) }
        return source
    }

    @Test
    fun liveResponseIsNotMarkedOffline() = runBlocking {
        val tracker = OfflineTracker()
        val result = CachedSource(FakeApi { saved }, directory, clock = later).fetch("A", tracker = tracker)
        assertEquals(saved, result)
        assertNull(tracker.offlineSince)
    }

    @Test
    fun networkFailureUsesSavedResponseAndMarksOffline() = runBlocking {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        api.result = { throw IOException("offline") }
        val tracker = OfflineTracker()
        assertEquals(saved, source.fetch("A", mapOf("q" to "1"), tracker))
        assertNotNull(tracker.offlineSince)
    }

    @Test
    fun serverErrorUsesSavedResponse() = runBlocking {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        api.result = { throw httpError(503) }
        assertEquals(saved, source.fetch("A", mapOf("q" to "1"), OfflineTracker()))
    }

    @Test
    fun tooManyRequestsUsesSavedResponse() = runBlocking {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        api.result = { throw httpError(429) }
        val tracker = OfflineTracker()
        assertEquals(saved, source.fetch("A", mapOf("q" to "1"), tracker))
        assertNotNull(tracker.offlineSince)
    }

    @Test
    fun unreadableReplyUsesSavedResponse() = runBlocking {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        api.result = { throw SerializationException("not JSON") }
        assertEquals(saved, source.fetch("A", mapOf("q" to "1"), OfflineTracker()))
    }

    @Test
    fun unreadableReplyWithNothingSavedIsAnError() {
        val source = CachedSource(FakeApi { throw SerializationException("not JSON") }, directory)
        assertThrows(SerializationException::class.java) {
            runBlocking { source.fetch("A", tracker = OfflineTracker()) }
        }
    }

    @Test
    fun replyWithoutDataDoesNotReplaceTheSavedOne() = runBlocking {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        val empty = JsonObject(mapOf("records" to JsonObject(emptyMap())))
        api.result = { empty }
        assertEquals(empty, source.fetch("A", mapOf("q" to "1"), OfflineTracker()))

        api.result = { throw IOException("offline") }
        assertEquals(saved, source.fetch("A", mapOf("q" to "1"), OfflineTracker()))
    }

    @Test
    fun copySavedMomentsAgoIsReturnedWithoutAskingAgain() = runBlocking {
        var requests = 0
        val source = CachedSource(FakeApi { requests++; saved }, directory)
        val tracker = OfflineTracker()
        source.fetch("A", tracker = tracker)
        assertEquals(saved, source.fetch("A", tracker = tracker))

        assertEquals(1, requests)
        assertNull(tracker.offlineSince)
    }

    @Test
    fun copyOlderThanTheFreshWindowIsAskedForAgain() = runBlocking {
        var requests = 0
        val source = CachedSource(FakeApi { requests++; response("reply $requests") }, directory, clock = later)
        source.fetch("A", tracker = OfflineTracker())
        assertEquals(response("reply 2"), source.fetch("A", tracker = OfflineTracker()))
        // A longer window, as used for town lists, still covers it.
        assertEquals(
            response("reply 2"),
            source.fetch("A", tracker = OfflineTracker(), freshMillis = 60 * 60 * 1000),
        )
        assertEquals(2, requests)
    }

    @Test
    fun rejectedKeyIsNotHiddenBySavedResponse() {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        api.result = { throw httpError(401) }
        assertThrows(HttpException::class.java) {
            runBlocking { source.fetch("A", mapOf("q" to "1"), OfflineTracker()) }
        }
    }

    private fun ageSavedResponses(hours: Long) {
        val time = System.currentTimeMillis() - hours * 60 * 60 * 1000
        directory.listFiles()!!.forEach { it.setLastModified(time) }
    }

    @Test
    fun expiredSavedResponseIsNotUsed() {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        ageSavedResponses(hours = 25)
        api.result = { throw IOException("offline") }
        assertThrows(IOException::class.java) {
            runBlocking { source.fetch("A", mapOf("q" to "1"), OfflineTracker()) }
        }
    }

    @Test
    fun recentSavedResponseIsStillUsed() = runBlocking {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        ageSavedResponses(hours = 23)
        api.result = { throw IOException("offline") }
        assertEquals(saved, source.fetch("A", mapOf("q" to "1"), OfflineTracker()))
    }

    @Test
    fun pruneDeletesOnlyExpiredResponses() {
        val source = sourceWithSavedResponse(FakeApi { saved })
        runBlocking { source.fetch("B", tracker = OfflineTracker()) }
        val old = directory.listFiles()!!.first()
        old.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000)
        source.prune()
        assertEquals(1, directory.listFiles()!!.size)
        assertEquals(false, old.exists())
    }

    @Test
    fun failedSaveDoesNotFailLiveResponse() = runBlocking {
        // A regular file where the cache directory should be makes every write fail.
        val blocker = Files.createTempFile("not-a-directory", "").toFile()
        val result = CachedSource(FakeApi { saved }, blocker).fetch("A", tracker = OfflineTracker())
        assertEquals(saved, result)
    }

    @Test
    fun differentQueryDoesNotReuseSavedResponse() {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        api.result = { throw IOException("offline") }
        assertThrows(IOException::class.java) {
            runBlocking { source.fetch("A", mapOf("q" to "2"), OfflineTracker()) }
        }
    }
}
