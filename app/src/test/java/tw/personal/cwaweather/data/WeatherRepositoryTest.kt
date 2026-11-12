package tw.personal.cwaweather.data

import java.io.IOException
import java.nio.file.Files
import java.time.OffsetDateTime
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class WeatherRepositoryTest {
    private val taipei = Place("臺北市", "中正區")
    private val newTaipeiXindian = Place("新北市", "新店區")
    private val now = OffsetDateTime.parse("2026-11-05T13:30:00+08:00")

    private fun fixture(name: String): JsonObject {
        val text = checkNotNull(javaClass.classLoader?.getResource(name)).readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    /** A station list holding one station with a temperature, in the shape the API returns. */
    private fun stationsJson(county: String, town: String, lat: Double, lon: Double, id: String = "S1") =
        Json.parseToJsonElement(
            """{"records":{"Station":[{"StationId":"$id","StationName":"測站","GeoInfo":{"Coordinates":[
               {"CoordinateName":"WGS84","StationLatitude":"$lat","StationLongitude":"$lon"}],
               "CountyName":"$county","TownName":"$town"},"ObsTime":{"DateTime":"2026-11-05T13:20:00+08:00"},
               "WeatherElement":{"AirTemperature":"24.5","Weather":"晴"}}]}}""",
        ).jsonObject

    private fun stationList(vararg lists: JsonObject) = Json.parseToJsonElement(
        """{"records":{"Station":${lists.flatMap { it.getValue("records").jsonObject.getValue("Station").jsonArray }}}}""",
    ).jsonObject

    private val noStations = Json.parseToJsonElement("""{"records":{"Station":[]}}""").jsonObject

    private val threeDayDatasets = Counties.names.mapNotNull(Counties::threeDayDataset).toSet()

    private class Call(val dataset: String, val query: Map<String, String>)

    private class ScriptedApi(private val respond: (Call) -> JsonObject) : CwaApi {
        val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())
        override suspend fun datastore(dataset: String, query: Map<String, String>): JsonObject {
            val call = Call(dataset, query)
            calls += call
            return respond(call)
        }
    }

    private fun httpError(code: Int) =
        HttpException(Response.error<JsonObject>(code, "".toResponseBody(null)))

    /** Answers each dataset with the captured fixtures, and the given station lists. */
    private fun api(
        synoptic: JsonObject = stationsJson("臺北市", "中正區", 25.04, 121.52),
        all: JsonObject = synoptic,
        override: (Call) -> JsonObject? = { null },
    ) = ScriptedApi { call ->
        override(call) ?: when {
            call.dataset == "O-A0003-001" -> synoptic
            call.dataset == "O-A0001-001" -> all
            call.dataset == "W-C0033-001" -> fixture("alerts_by_county.json")
            call.dataset == "W-C0033-002" -> fixture("alerts_detail.json")
            call.dataset in threeDayDatasets -> fixture("hourly.json")
            else -> fixture("daily.json")
        }
    }

    private fun repository(api: CwaApi, verify: suspend (String) -> Unit = {}) = WeatherRepository(
        // Ten minutes ahead of the files' real timestamps, so every load asks the API again.
        source = CachedSource(
            api,
            Files.createTempDirectory("api-cache").toFile(),
            clock = { System.currentTimeMillis() + 10 * 60 * 1000 },
        ),
        verify = verify,
        now = { now },
    )

    @Test
    fun loadForPlaceCombinesObservationForecastsAndAlerts() = runBlocking {
        val snapshot = repository(api()).loadForPlace(taipei)

        assertEquals(taipei, snapshot.place)
        assertEquals("S1", snapshot.current?.station?.id)
        assertEquals(24.5, snapshot.current?.station?.temperature)
        assertTrue(snapshot.hourly!!.isNotEmpty())
        assertTrue(snapshot.daily!!.isNotEmpty())
        assertEquals(emptyList<WeatherAlert>(), snapshot.alerts)
        assertNull(snapshot.offlineSince)
    }

    @Test
    fun forecastsAskOnlyForTheTownAndTheNeededElements() = runBlocking {
        val api = api()
        repository(api).loadForPlace(taipei)

        val hourly = api.calls.single { it.dataset == "F-D0047-061" }
        assertEquals("中正區", hourly.query["LocationName"])
        assertEquals("time", hourly.query["sort"])
        assertTrue(checkNotNull(hourly.query["ElementName"]).contains("溫度"))
        val daily = api.calls.single { it.dataset == "F-D0047-063" }
        assertEquals("中正區", daily.query["LocationName"])
        assertTrue(checkNotNull(daily.query["ElementName"]).contains("最高溫度"))
    }

    @Test
    fun alertsCarryTheirFullTextForTheCounty() = runBlocking {
        val api = api(synoptic = stationsJson("新北市", "新店區", 24.96, 121.51))
        val snapshot = repository(api).loadForPlace(newTaipeiXindian)

        val alert = checkNotNull(snapshot.alerts).single()
        assertNotNull(alert.detail)
    }

    @Test
    fun alertFailureStillShowsTheForecast() = runBlocking {
        val api = api(override = { call -> if (call.dataset.startsWith("W-C0033")) throw IOException("down") else null })
        val snapshot = repository(api).loadForPlace(taipei)

        assertNull(snapshot.alerts)
        assertTrue(snapshot.hourly!!.isNotEmpty())
        assertNotNull(snapshot.current)
    }

    @Test
    fun unknownCountyHasNoForecastRequests() = runBlocking {
        val api = api(synoptic = noStations)
        val snapshot = repository(api).loadForPlace(Place("不存在市", "某區"))

        assertEquals(emptyList<HourlyForecast>(), snapshot.hourly)
        assertEquals(emptyList<DailyForecast>(), snapshot.daily)
        assertNull(snapshot.current)
        assertTrue(api.calls.none { it.dataset.startsWith("F-D0047") })
    }

    @Test
    fun largerStationListIsSkippedWhenSynopticStationsCoverTheTown() = runBlocking {
        val api = api()
        repository(api).loadForPlace(taipei)
        assertTrue(api.calls.none { it.dataset == "O-A0001-001" })
    }

    @Test
    fun largerStationListFillsInWhenSynopticStationsDoNot() = runBlocking {
        val api = api(synoptic = noStations, all = stationsJson("臺北市", "中正區", 25.04, 121.52, id = "ALL"))
        val snapshot = repository(api).loadForPlace(taipei)

        assertEquals("ALL", snapshot.current?.station?.id)
        assertTrue(api.calls.any { it.dataset == "O-A0001-001" })
    }

    @Test
    fun stationInTheSameCountyIsUsedWhenTheTownHasNone() = runBlocking {
        val api = api(synoptic = stationsJson("臺北市", "信義區", 25.03, 121.56, id = "NEARBY"))
        val snapshot = repository(api).loadForPlace(taipei)
        assertEquals("NEARBY", snapshot.current?.station?.id)
    }

    // The forecast fixture puts 中正區 at 25.046, 121.517.
    @Test
    fun townWithoutAStationUsesTheNearestOneNotTheFirstListed() = runBlocking {
        val api = api(
            synoptic = stationList(
                stationsJson("臺北市", "北投區", 25.18, 121.54, id = "FAR"),
                stationsJson("臺北市", "信義區", 25.03, 121.56, id = "NEARBY"),
            ),
        )
        val current = repository(api).loadForPlace(taipei).current

        assertEquals("NEARBY", current?.station?.id)
        assertEquals(4.7, current!!.distanceKm!!, 0.5)
    }

    @Test
    fun nearestStationMayBeInANeighbouringCounty() = runBlocking {
        val api = api(
            synoptic = stationsJson("臺北市", "北投區", 25.18, 121.54, id = "SAME_COUNTY"),
            all = stationsJson("新北市", "永和區", 25.01, 121.51, id = "NEIGHBOUR"),
        )
        assertEquals("NEIGHBOUR", repository(api).loadForPlace(taipei).current?.station?.id)
    }

    @Test
    fun withoutTheTownsPositionAStationInTheCountyIsUsed() = runBlocking {
        val noPosition = Json.parseToJsonElement(
            """{"records":{"Locations":[{"Location":[{"LocationName":"中正區","WeatherElement":[]}]}]}}""",
        ).jsonObject
        val api = api(
            synoptic = stationsJson("新北市", "永和區", 25.01, 121.51, id = "NEIGHBOUR"),
            all = stationsJson("臺北市", "北投區", 25.18, 121.54, id = "SAME_COUNTY"),
            override = { call -> noPosition.takeIf { call.dataset in threeDayDatasets } },
        )
        val current = repository(api).loadForPlace(taipei).current

        assertEquals("SAME_COUNTY", current?.station?.id)
        assertNull(current?.distanceKm)
    }

    @Test
    fun stationInTheTownIsKeptEvenWhenAnotherIsCloser() = runBlocking {
        val api = api(
            synoptic = stationList(
                stationsJson("新北市", "永和區", 25.045, 121.517, id = "CLOSER"),
                stationsJson("臺北市", "中正區", 25.03, 121.51, id = "IN_TOWN"),
            ),
        )
        val current = repository(api).loadForPlace(taipei).current

        assertEquals("IN_TOWN", current?.station?.id)
        assertNull(current?.distanceKm)
    }

    @Test
    fun savedResponsesAnswerWhenTheServiceCannotBeReached() = runBlocking {
        var offline = false
        val api = api(override = { if (offline) throw IOException("no network") else null })
        val repository = repository(api)

        val live = repository.loadForPlace(taipei)
        assertNull(live.offlineSince)

        offline = true
        val saved = repository.loadForPlace(taipei)
        assertNotNull(saved.offlineSince)
        assertEquals(live.hourly, saved.hourly)
        assertEquals(live.current, saved.current)
    }

    private fun failing(vararg datasets: String, error: () -> Exception = { IOException("down") }) =
        { call: Call -> if (call.dataset in datasets) throw error() else null }

    @Test
    fun observationFailureStillShowsTheForecasts() = runBlocking {
        val snapshot = repository(api(override = failing("O-A0003-001"))).loadForPlace(taipei)

        assertNull(snapshot.current)
        assertTrue(snapshot.currentUnavailable)
        assertTrue(snapshot.hourly!!.isNotEmpty())
        assertTrue(snapshot.daily!!.isNotEmpty())
    }

    @Test
    fun threeDayFailureStillShowsTheRest() = runBlocking {
        val api = api(
            synoptic = stationsJson("臺北市", "信義區", 25.03, 121.56, id = "NEARBY"),
            override = failing("F-D0047-061", error = { httpError(500) }),
        )
        val snapshot = repository(api).loadForPlace(taipei)

        assertNull(snapshot.hourly)
        assertTrue(snapshot.daily!!.isNotEmpty())
        // The town's position is unknown without that response, so the county's station is used.
        assertEquals("NEARBY", snapshot.current?.station?.id)
        assertFalse(snapshot.currentUnavailable)
    }

    @Test
    fun weeklyFailureStillShowsTheRest() = runBlocking {
        val snapshot = repository(api(override = failing("F-D0047-063"))).loadForPlace(taipei)

        assertNull(snapshot.daily)
        assertTrue(snapshot.hourly!!.isNotEmpty())
        assertNotNull(snapshot.current)
    }

    @Test
    fun townMissingFromTheForecastIsEmptyNotAFailure() = runBlocking {
        val api = api(synoptic = stationsJson("臺北市", "大安區", 25.03, 121.54))
        val snapshot = repository(api).loadForPlace(Place("臺北市", "大安區"))

        assertEquals(emptyList<HourlyForecast>(), snapshot.hourly)
        assertEquals(emptyList<DailyForecast>(), snapshot.daily)
        assertFalse(snapshot.currentUnavailable)
    }

    @Test
    fun everySectionFailingIsAnErrorEvenIfAlertsLoad() {
        val api = api(override = failing("O-A0003-001", "F-D0047-061", "F-D0047-063"))
        assertThrows(IOException::class.java) { runBlocking { repository(api).loadForPlace(taipei) } }
    }

    @Test
    fun rejectedKeyOnOneSectionFailsTheWholeLoad() {
        val api = api(override = failing("F-D0047-063", error = { httpError(403) }))
        val error = assertThrows(HttpException::class.java) { runBlocking { repository(api).loadForPlace(taipei) } }
        assertEquals(403, error.code())
    }

    @Test
    fun deviceLocationKeepsItsObservationWhenForecastsFail() = runBlocking {
        val api = api(override = failing("F-D0047-061", "F-D0047-063"))
        val snapshot = repository(api).loadAtCoordinates(25.0401, 121.5201)

        assertNotNull(snapshot.current)
        assertNull(snapshot.hourly)
        assertNull(snapshot.daily)
    }

    @Test
    fun unreachableServiceWithNothingSavedIsAnError() {
        val api = api(override = { throw IOException("no network") })
        assertThrows(IOException::class.java) { runBlocking { repository(api).loadForPlace(taipei) } }
    }

    @Test
    fun rejectedKeyIsAnErrorEvenWithSavedData() = runBlocking {
        var rejected = false
        val api = api(override = { if (rejected) throw httpError(401) else null })
        val repository = repository(api)
        repository.loadForPlace(taipei)

        rejected = true
        val error = assertThrows(HttpException::class.java) { runBlocking { repository.loadForPlace(taipei) } }
        assertEquals(401, error.code())
    }

    @Test
    fun coordinatesResolveToTheNearestStationsTown() = runBlocking {
        val snapshot = repository(api()).loadAtCoordinates(25.0401, 121.5201)

        assertEquals(taipei, snapshot.place)
        assertEquals(taipei, snapshot.current?.station?.let { Place(it.county, it.town) })
        assertTrue(checkNotNull(snapshot.current?.distanceKm) < 1.0)
        assertTrue(snapshot.hourly!!.isNotEmpty())
    }

    @Test
    fun coordinatesOutsideTaiwanAreRejected() {
        assertThrows(OutsideTaiwanException::class.java) {
            runBlocking { repository(api()).loadAtCoordinates(35.68, 139.69) }
        }
    }

    @Test
    fun townsComeFromTheCountyForecastDataset() = runBlocking {
        val api = api()
        assertEquals(listOf("中正區"), repository(api).towns("臺北市"))
        assertEquals("F-D0047-061", api.calls.single().dataset)
    }

    @Test
    fun repeatedLoadsWithinMomentsDoNotDownloadAgain() = runBlocking {
        val api = api()
        val repository = WeatherRepository(
            source = CachedSource(api, Files.createTempDirectory("api-cache").toFile()),
            verify = {},
            now = { now },
        )
        val first = repository.loadForPlace(taipei)
        val requests = api.calls.size
        val second = repository.loadForPlace(taipei)
        repository.towns("臺北市")
        repository.towns("臺北市")

        assertEquals(requests + 1, api.calls.size)
        assertEquals(first, second)
        assertNull(second.offlineSince)
    }

    @Test
    fun townsOfAnUnknownCountyAreEmptyWithoutARequest() = runBlocking {
        val api = api()
        assertEquals(emptyList<String>(), repository(api).towns("不存在市"))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun verifyKeyPassesTheCandidateAndPropagatesFailure() {
        val seen = mutableListOf<String>()
        val repository = repository(api()) { candidate ->
            seen += candidate
            if (candidate == "bad") throw httpError(403)
        }
        runBlocking { repository.verifyKey("good") }
        assertThrows(HttpException::class.java) { runBlocking { repository.verifyKey("bad") } }
        assertEquals(listOf("good", "bad"), seen)
        assertFalse(seen.isEmpty())
    }
}
