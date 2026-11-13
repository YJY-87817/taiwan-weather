package tw.personal.cwaweather.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

/** Runs the real Retrofit and OkHttp stack against a local server. */
class CwaApiTest {
    private val server = MockWebServer()
    private var key = "KEY-1"
    private lateinit var api: CwaApi

    @Before
    fun setUp() {
        server.start()
        api = CwaApi.create(baseUrl = server.url("/api/").toString()) { key }
    }

    @After
    fun tearDown() = server.close()

    private fun reply(body: String, code: Int = 200) =
        server.enqueue(MockResponse(code = code, body = body))

    @Test
    fun requestNamesTheDatasetAndCarriesTheKeyAndQuery() = runBlocking {
        reply("""{"records":{"value":"ok"}}""")
        val response = api.datastore("F-D0047-061", mapOf("LocationName" to "中正區", "sort" to "time"))

        val url = server.takeRequest().url
        assertEquals("/api/v1/rest/datastore/F-D0047-061", url.encodedPath)
        assertEquals("KEY-1", url.queryParameter("Authorization"))
        assertEquals("中正區", url.queryParameter("LocationName"))
        assertEquals("time", url.queryParameter("sort"))
        assertEquals(JsonPrimitive("ok"), (response["records"] as Map<*, *>)["value"])
    }

    @Test
    fun changedKeyAppliesToTheNextRequest() = runBlocking {
        reply("{}")
        reply("{}")
        api.datastore("A")
        key = "KEY-2"
        api.datastore("A")

        assertEquals("KEY-1", server.takeRequest().url.queryParameter("Authorization"))
        assertEquals("KEY-2", server.takeRequest().url.queryParameter("Authorization"))
    }

    @Test
    fun rejectedKeyIsAnHttpError() {
        reply("""{"message":"Unauthorized"}""", code = 401)
        val error = assertThrows(HttpException::class.java) { runBlocking { api.datastore("A") } }
        assertEquals(401, error.code())
    }

    // The key check and the cache both rely on this being the failure for a non-JSON reply.
    @Test
    fun replyThatIsNotJsonIsASerializationError() {
        reply("<html>maintenance</html>")
        assertThrows(SerializationException::class.java) { runBlocking { api.datastore("A") } }
    }
}
