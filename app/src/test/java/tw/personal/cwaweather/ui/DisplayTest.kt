package tw.personal.cwaweather.ui

import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.personal.cwaweather.data.Place
import tw.personal.cwaweather.data.Station

class DisplayTest {
    private fun station(precipitation: Double? = null, trace: Boolean = false) = Station(
        id = "S", name = "S", county = "臺北市", town = "中正區", latitude = 25.0, longitude = 121.5,
        observedAt = null, temperature = 20.0, humidity = null, weather = null, windSpeed = null,
        windDirection = null, pressure = null, uvIndex = null, precipitation = precipitation,
        dailyHigh = null, dailyLow = null, precipitationTrace = trace,
    )

    @Test
    fun favoriteWithAUniqueTownShowsOnlyTheTown() {
        val favorites = listOf(Place("臺北市", "中正區"), Place("高雄市", "左營區"))
        assertEquals("中正區", favoriteLabel(favorites[0], favorites))
    }

    @Test
    fun favoritesSharingATownNameShowTheirCounty() {
        val favorites = listOf(Place("臺南市", "東區"), Place("新竹市", "東區"), Place("高雄市", "左營區"))
        assertEquals("臺南市東區", favoriteLabel(favorites[0], favorites))
        assertEquals("新竹市東區", favoriteLabel(favorites[1], favorites))
        assertEquals("左營區", favoriteLabel(favorites[2], favorites))
    }

    @Test
    fun windDirectionIsNamedByCompassPoint() {
        assertEquals("北", windDirectionText(3.0, 360.0))
        assertEquals("北", windDirectionText(3.0, 10.0))
        assertEquals("東", windDirectionText(3.0, 90.0))
        assertEquals("西北", windDirectionText(null, 315.0))
        assertEquals("—", windDirectionText(3.0, null))
    }

    @Test
    fun noWindIsCalmWhateverTheDirectionValue() {
        assertEquals("靜風", windDirectionText(0.0, 0.0))
        assertEquals("靜風", windDirectionText(0.0, 180.0))
        assertEquals("靜風", windDirectionText(0.0, null))
    }

    @Test
    fun rainIsAnAmountATraceOrUnknown() {
        assertEquals("1.5 mm", rainText(station(precipitation = 1.5)))
        assertEquals("0.0 mm", rainText(station(precipitation = 0.0)))
        assertEquals("微量", rainText(station(trace = true)))
        assertEquals("—", rainText(station()))
    }

    private fun time(text: String) = OffsetDateTime.parse(text)

    @Test
    fun chartPlacesTimesByHowFarApartTheyAre() {
        // One hour, then three hours: the middle point belongs a quarter of the way along, not half.
        val times = listOf(time("2026-11-05T12:00+08:00"), time("2026-11-05T13:00+08:00"), time("2026-11-05T16:00+08:00"))
        val scale = ChartScale(times)
        assertEquals(0f, scale.fraction(times[0]), 0f)
        assertEquals(0.25f, scale.fraction(times[1]), 1e-6f)
        assertEquals(1f, scale.fraction(times[2]), 0f)
    }

    @Test
    fun chartMarksEachTaiwanMidnightInRange() {
        val scale = ChartScale(listOf(time("2026-11-05T12:00+08:00"), time("2026-11-07T09:00+08:00")))
        assertEquals(
            listOf(time("2026-11-06T00:00+08:00"), time("2026-11-07T00:00+08:00")),
            scale.dayStarts,
        )
    }

    @Test
    fun chartDaysFollowTaiwanTimeWhateverOffsetTheTimesCarry() {
        // 04:00 UTC to 20:00 UTC is 12:00 to 04:00 the next day in Taiwan.
        val scale = ChartScale(listOf(time("2026-11-05T04:00Z"), time("2026-11-05T20:00Z")))
        assertEquals(1, scale.dayStarts.size)
        assertTrue(scale.dayStarts.single().isEqual(time("2026-11-06T00:00+08:00")))
    }

    @Test
    fun chartWithASingleInstantDoesNotDivideByZero() {
        val only = time("2026-11-05T12:00+08:00")
        assertEquals(0f, ChartScale(listOf(only, only)).fraction(only), 0f)
    }
}
