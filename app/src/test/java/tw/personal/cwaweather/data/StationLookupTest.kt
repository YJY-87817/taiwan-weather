package tw.personal.cwaweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StationLookupTest {
    private fun station(id: String, county: String, town: String, lat: Double, lon: Double) = Station(
        id = id, name = id, county = county, town = town, latitude = lat, longitude = lon,
        observedAt = null, temperature = 20.0, humidity = null, weather = null, windSpeed = null,
        windDirection = null, pressure = null, uvIndex = null, precipitation = null,
        dailyHigh = null, dailyLow = null,
    )

    // Taipei Main Station and Taipei 101 are about 5.5 km apart.
    private val taipeiStation = station("A", "臺北市", "中正區", 25.0478, 121.5170)
    private val xinyi = station("B", "臺北市", "信義區", 25.0330, 121.5654)
    private val kaohsiung = station("C", "高雄市", "左營區", 22.6273, 120.3014)

    @Test
    fun nearestStationComesWithItsDistance() {
        val (station, distance) = nearestStation(Coordinates(25.03, 121.56), listOf(kaohsiung, taipeiStation, xinyi))!!
        assertSame(xinyi, station)
        assertTrue(distance < 1.0)
        assertNull(nearestStation(Coordinates(25.03, 121.56), emptyList()))
    }

    @Test
    fun distanceBetweenIdenticalPointsIsZero() {
        assertEquals(0.0, distanceKm(25.0, 121.5, 25.0, 121.5), 1e-9)
    }

    @Test
    fun distanceIsSymmetricAndRoughlyRight() {
        val forward = distanceKm(25.0478, 121.5170, 22.6273, 120.3014)
        val backward = distanceKm(22.6273, 120.3014, 25.0478, 121.5170)
        assertEquals(forward, backward, 1e-9)
        // Taipei to Kaohsiung is about 290 km in a straight line.
        assertTrue(forward in 280.0..300.0)
    }

    @Test
    fun townComesFromTheNearestStationOfEitherList() {
        val (place, current) = resolveNearest(25.0335, 121.5650, listOf(taipeiStation), listOf(xinyi, kaohsiung))
        assertEquals(Place("臺北市", "信義區"), place)
        // The synoptic station is within range, so it supplies the observation.
        assertSame(taipeiStation, current.station)
    }

    @Test
    fun observationUsesNearestStationWhenSynopticIsTooFar() {
        val (place, current) = resolveNearest(22.6273, 120.3014, listOf(taipeiStation), listOf(kaohsiung))
        assertEquals(Place("高雄市", "左營區"), place)
        assertSame(kaohsiung, current.station)
        assertNotNull(current.distanceKm)
        assertEquals(0.0, current.distanceKm!!, 1e-6)
    }

    @Test
    fun synopticStationBeyondPreferredRangeIsNotUsed() {
        // About 22 km east of Taipei Main Station: beyond the 15 km preference, so the town's own station wins.
        val nearAll = station("D", "新北市", "瑞芳區", 25.0478, 121.7300)
        val (_, current) = resolveNearest(25.0478, 121.7300, listOf(taipeiStation), listOf(nearAll))
        assertSame(nearAll, current.station)
    }

    @Test
    fun stationsWithoutATownDoNotDecideThePlace() {
        val noTown = station("E", "臺北市", "", 25.0335, 121.5650)
        val (place, _) = resolveNearest(25.0335, 121.5650, listOf(noTown), listOf(xinyi))
        assertEquals("信義區", place.town)
    }

    @Test
    fun farFromEveryStationIsOutsideTaiwan() {
        // Tokyo.
        assertThrows(OutsideTaiwanException::class.java) {
            resolveNearest(35.68, 139.69, listOf(taipeiStation), listOf(xinyi, kaohsiung))
        }
    }

    @Test
    fun noUsableStationsMeansLocationUnavailable() {
        assertThrows(LocationUnavailableException::class.java) {
            resolveNearest(25.0, 121.5, emptyList(), emptyList())
        }
    }
}
