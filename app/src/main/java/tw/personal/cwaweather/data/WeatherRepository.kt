package tw.personal.cwaweather.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class OutsideTaiwanException : Exception()
class LocationUnavailableException : Exception()

class WeatherRepository(private val context: Context, credentials: CredentialStore) {
    private val source = CachedSource(CwaApi.create { credentials.apiKey }, File(context.filesDir, "api-cache"))

    /** Throws if CWA rejects the key or cannot be reached. Never answered from the cache. */
    suspend fun verifyKey(candidate: String) {
        CwaApi.create { candidate }.datastore("W-C0033-001")
    }

    suspend fun loadForPlace(place: Place): WeatherSnapshot = withContext(Dispatchers.IO) {
        val tracker = OfflineTracker()
        val current = currentForPlace(place, tracker)
        snapshot(place, current, tracker)
    }

    suspend fun loadAtCoordinates(latitude: Double, longitude: Double): WeatherSnapshot =
        withContext(Dispatchers.IO) {
            val tracker = OfflineTracker()
            val (place, current) = resolveByCoordinates(latitude, longitude, tracker)
            snapshot(place, current, tracker)
        }

    suspend fun towns(county: String): List<String> = withContext(Dispatchers.IO) {
        val dataset = Counties.threeDayDataset(county) ?: return@withContext emptyList()
        parseTownNames(source.fetch(dataset, mapOf("ElementName" to "溫度"), OfflineTracker()))
    }

    /** Callers must already hold ACCESS_COARSE_LOCATION. */
    @SuppressLint("MissingPermission")
    suspend fun deviceLocation(): Location {
        val client = LocationServices.getFusedLocationProviderClient(context)
        return client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            ?: client.lastLocation.await()
            ?: throw LocationUnavailableException()
    }

    private suspend fun snapshot(place: Place, current: CurrentWeather?, tracker: OfflineTracker) =
        coroutineScope {
            val hourly = async { hourly(place, tracker) }
            val daily = async { daily(place, tracker) }
            val alerts = async { alerts(place.county, tracker) }
            WeatherSnapshot(
                place = place,
                current = current,
                hourly = hourly.await(),
                daily = daily.await(),
                alerts = alerts.await(),
                offlineSince = tracker.offlineSince,
            )
        }

    private suspend fun stations(dataset: String, tracker: OfflineTracker) =
        parseStations(source.fetch(dataset, tracker = tracker)).filter { it.temperature != null }

    /** The dense station list decides the town; the synoptic list is preferred for observations. */
    private suspend fun resolveByCoordinates(
        latitude: Double,
        longitude: Double,
        tracker: OfflineTracker,
    ): Pair<Place, CurrentWeather> {
        val synoptic = stations(SYNOPTIC_DATASET, tracker)
        val all = stations(ALL_STATIONS_DATASET, tracker)

        fun List<Station>.nearest() = map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .minByOrNull { it.second }

        val closest = (synoptic + all).filter { it.town.isNotBlank() }.nearest()
            ?: throw LocationUnavailableException()
        if (closest.second > MAX_DISTANCE_FROM_STATION_KM) throw OutsideTaiwanException()

        val closestSynoptic = synoptic.nearest()
        val observation = closestSynoptic?.takeIf { it.second <= PREFERRED_SYNOPTIC_RANGE_KM } ?: closest
        return Place(closest.first.county, closest.first.town) to CurrentWeather(observation.first, observation.second)
    }

    /** Prefers a synoptic station in the town, then any station there, then one in the county. */
    private suspend fun currentForPlace(place: Place, tracker: OfflineTracker): CurrentWeather? {
        val synoptic = stations(SYNOPTIC_DATASET, tracker)
        synoptic.firstOrNull { it.inTown(place) }?.let { return CurrentWeather(it, null) }
        val all = stations(ALL_STATIONS_DATASET, tracker)
        val station = all.firstOrNull { it.inTown(place) }
            ?: synoptic.firstOrNull { it.county == place.county }
            ?: all.firstOrNull { it.county == place.county }
        return station?.let { CurrentWeather(it, null) }
    }

    private suspend fun hourly(place: Place, tracker: OfflineTracker): List<HourlyForecast> {
        val dataset = Counties.threeDayDataset(place.county) ?: return emptyList()
        val root = source.fetch(
            dataset,
            mapOf("LocationName" to place.town, "ElementName" to "溫度,天氣現象,3小時降雨機率", "sort" to "time"),
            tracker,
        )
        return parseHourly(root, place.town, OffsetDateTime.now())
    }

    private suspend fun daily(place: Place, tracker: OfflineTracker): List<DailyForecast> {
        val dataset = Counties.weeklyDataset(place.county) ?: return emptyList()
        val root = source.fetch(
            dataset,
            mapOf(
                "LocationName" to place.town,
                "ElementName" to "最高溫度,最低溫度,天氣現象,12小時降雨機率",
                "sort" to "time",
            ),
            tracker,
        )
        return parseDaily(root, place.town, OffsetDateTime.now())
    }

    /** A failure here returns null so the forecast can still be shown. */
    private suspend fun alerts(county: String, tracker: OfflineTracker): List<WeatherAlert>? = try {
        parseAlerts(
            byCounty = source.fetch("W-C0033-001", tracker = tracker),
            details = source.fetch("W-C0033-002", tracker = tracker),
            county = county,
            now = OffsetDateTime.now(),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun Station.inTown(place: Place) = county == place.county && town == place.town

    private companion object {
        const val SYNOPTIC_DATASET = "O-A0003-001"
        const val ALL_STATIONS_DATASET = "O-A0001-001"
        const val PREFERRED_SYNOPTIC_RANGE_KM = 15.0
        const val MAX_DISTANCE_FROM_STATION_KM = 100.0

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
