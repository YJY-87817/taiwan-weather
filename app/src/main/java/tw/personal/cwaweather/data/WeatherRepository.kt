package tw.personal.cwaweather.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class OutsideTaiwanException : Exception()
class LocationUnavailableException : Exception()

class WeatherRepository(private val context: Context, credentials: CredentialStore) {
    private val source = CachedSource(CwaApi.create { credentials.apiKey }, File(context.filesDir, "api-cache"))

    /** Throws if CWA rejects the key or cannot be reached. Never answered from the cache. */
    suspend fun verifyKey(candidate: String) {
        CwaApi.create { candidate }.datastore("W-C0033-001")
    }

    suspend fun loadForPlace(place: Place): WeatherSnapshot = withContext(Dispatchers.IO) {
        source.prune()
        val tracker = OfflineTracker()
        // The observation loads alongside the forecasts instead of before them.
        coroutineScope {
            val current = async { currentForPlace(place, tracker) }
            snapshot(place, current, tracker)
        }
    }

    suspend fun loadAtCoordinates(latitude: Double, longitude: Double): WeatherSnapshot =
        withContext(Dispatchers.IO) {
            source.prune()
            val tracker = OfflineTracker()
            val (place, current) = resolveByCoordinates(latitude, longitude, tracker)
            snapshot(place, CompletableDeferred(current), tracker)
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

    private suspend fun snapshot(
        place: Place,
        current: Deferred<CurrentWeather?>,
        tracker: OfflineTracker,
    ) =
        coroutineScope {
            val hourly = async { hourly(place, tracker) }
            val daily = async { daily(place, tracker) }
            val alerts = async { alerts(place.county, tracker) }
            WeatherSnapshot(
                place = place,
                current = current.await(),
                hourly = hourly.await(),
                daily = daily.await(),
                alerts = alerts.await(),
                offlineSince = tracker.offlineSince,
            )
        }

    private suspend fun stations(dataset: String, tracker: OfflineTracker) =
        parseStations(source.fetch(dataset, tracker = tracker)).filter { it.temperature != null }

    private suspend fun resolveByCoordinates(
        latitude: Double,
        longitude: Double,
        tracker: OfflineTracker,
    ): Pair<Place, CurrentWeather> {
        val (synoptic, all) = coroutineScope {
            val synopticStations = async { stations(SYNOPTIC_DATASET, tracker) }
            val allStations = async { stations(ALL_STATIONS_DATASET, tracker) }
            synopticStations.await() to allStations.await()
        }
        return resolveNearest(latitude, longitude, synoptic, all)
    }

    /** Prefers a synoptic station in the town, then any station there, then one in the county. */
    private suspend fun currentForPlace(place: Place, tracker: OfflineTracker): CurrentWeather? {
        // The larger list is only requested if the synoptic stations do not cover the town.
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
    }
}
