package tw.personal.cwaweather.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
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
    private val saved = JsonObject(mapOf("value" to JsonPrimitive("saved")))
    private val directory: File = Files.createTempDirectory("api-cache").toFile()

    private class FakeApi(var result: () -> JsonObject) : CwaApi {
        override suspend fun datastore(dataset: String, query: Map<String, String>) = result()
    }

    private fun httpError(code: Int) =
        HttpException(Response.error<JsonObject>(code, "".toResponseBody(null)))

    private fun sourceWithSavedResponse(api: FakeApi): CachedSource {
        val source = CachedSource(api, directory)
        runBlocking { source.fetch("A", mapOf("q" to "1"), OfflineTracker()) }
        return source
    }

    @Test
    fun liveResponseIsNotMarkedOffline() = runBlocking {
        val tracker = OfflineTracker()
        val result = CachedSource(FakeApi { saved }, directory).fetch("A", tracker = tracker)
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
    fun rejectedKeyIsNotHiddenBySavedResponse() {
        val api = FakeApi { saved }
        val source = sourceWithSavedResponse(api)
        api.result = { throw httpError(401) }
        assertThrows(HttpException::class.java) {
            runBlocking { source.fetch("A", mapOf("q" to "1"), OfflineTracker()) }
        }
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
