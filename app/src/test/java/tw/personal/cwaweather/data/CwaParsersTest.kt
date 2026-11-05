package tw.personal.cwaweather.data

import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real CWA responses captured with the same queries the app sends. */
class CwaParsersTest {
    private fun fixture(name: String): JsonObject {
        val text = checkNotNull(javaClass.classLoader?.getResource(name)).readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private val capturedAt = OffsetDateTime.parse("2026-11-05T13:30:00+08:00")

    @Test
    fun stationsReadCoordinatesAndObservations() {
        val stations = parseStations(fixture("stations.json"))
        assertEquals(3, stations.size)
        val station = stations.first()
        assertTrue(station.name.isNotBlank())
        assertTrue(station.county.isNotBlank())
        assertTrue(station.latitude in 21.0..27.0)
        assertTrue(station.longitude in 117.0..123.0)
        assertNotNull(station.observedAt)
        assertNotNull(station.temperature)
    }

    @Test
    fun hourlyMergesTemperatureWeatherAndRain() {
        val hourly = parseHourly(fixture("hourly.json"), "中正區", capturedAt)
        assertTrue(hourly.size > 20)
        assertTrue(hourly.all { it.temperature in -20.0..50.0 })
        assertTrue(hourly.count { it.weather != null } > 20)
        assertTrue(hourly.count { it.rainChance != null } > 5)
        assertEquals(hourly.sortedBy { it.time }, hourly)
    }

    @Test
    fun dailyGroupsPeriodsByDate() {
        val daily = parseDaily(fixture("daily.json"), "中正區", capturedAt)
        assertTrue(daily.size in 6..8)
        assertEquals(capturedAt.toLocalDate(), daily.first().date)
        assertEquals(daily.map { it.date }.distinct(), daily.map { it.date })
        assertTrue(daily.all { it.weather != null })
        daily.forEach { day ->
            val high = day.high
            val low = day.low
            if (high != null && low != null) assertTrue(high >= low)
        }
        assertTrue(daily.count { it.high != null && it.low != null } >= 6)
    }

    @Test
    fun alertsJoinCountyListWithFullText() {
        val alerts = parseAlerts(fixture("alerts_by_county.json"), fixture("alerts_detail.json"), "新北市", capturedAt)
        val alert = alerts.single()
        assertEquals("陸上強風特報", alert.title)
        assertEquals(OffsetDateTime.parse("2026-11-05T23:00:00+08:00"), alert.start)
        assertEquals(OffsetDateTime.parse("2026-11-06T23:00:00+08:00"), alert.end)
        assertTrue(alert.detail.orEmpty().startsWith("一、概述"))
    }

    @Test
    fun endedAlertsAreDropped() {
        val afterEnd = OffsetDateTime.parse("2026-11-06T23:00:00+08:00")
        assertTrue(
            parseAlerts(fixture("alerts_by_county.json"), fixture("alerts_detail.json"), "新北市", afterEnd).isEmpty(),
        )
    }

    @Test
    fun countyWithoutHazardsHasNoAlerts() {
        val noHazards = Json.parseToJsonElement(
            """{"records":{"location":[{"locationName":"臺北市","hazardConditions":{"hazards":[]}}]}}""",
        ).jsonObject
        assertTrue(parseAlerts(noHazards, fixture("alerts_detail.json"), "臺北市", capturedAt).isEmpty())
    }

    @Test
    fun unknownTownGivesNoForecast() {
        assertTrue(parseHourly(fixture("hourly.json"), "不存在區", capturedAt).isEmpty())
    }

    @Test
    fun missingValueSentinelsBecomeNull() {
        val root = Json.parseToJsonElement(
            """{"records":{"Station":[{"StationId":"X","StationName":"測","GeoInfo":{"Coordinates":[
               {"CoordinateName":"WGS84","StationLatitude":"25.0","StationLongitude":"121.5"}],
               "CountyName":"臺北市","TownName":"中正區"},"ObsTime":{"DateTime":"-99"},
               "WeatherElement":{"AirTemperature":"-99","Weather":"-99","RelativeHumidity":"-99.0"}}]}}""",
        ).jsonObject
        val station = parseStations(root).single()
        assertNull(station.temperature)
        assertNull(station.weather)
        assertNull(station.humidity)
        assertNull(station.observedAt)
    }

    @Test
    fun everyCountyMapsToTheDocumentedDatasets() {
        assertEquals(22, Counties.names.size)
        assertEquals("F-D0047-061", Counties.threeDayDataset("臺北市"))
        assertEquals("F-D0047-063", Counties.weeklyDataset("臺北市"))
        assertEquals("F-D0047-087", Counties.weeklyDataset("金門縣"))
    }
}
