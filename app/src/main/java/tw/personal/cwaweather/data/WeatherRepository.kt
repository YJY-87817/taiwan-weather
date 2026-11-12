package tw.personal.cwaweather.data

import android.content.Context
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException

class WeatherRepository(
    private val source: CachedSource,
    private val verify: suspend (String) -> Unit,
    private val now: () -> OffsetDateTime = OffsetDateTime::now,
) : WeatherSource {
    override suspend fun verifyKey(candidate: String) = verify(candidate)

    override suspend fun loadForPlace(place: Place): WeatherSnapshot = withContext(Dispatchers.IO) {
        source.prune()
        val tracker = OfflineTracker()
        snapshot(place, tracker) { townCoordinates -> currentForPlace(place, tracker, townCoordinates) }
    }

    override suspend fun loadAtCoordinates(latitude: Double, longitude: Double): WeatherSnapshot =
        withContext(Dispatchers.IO) {
            source.prune()
            val tracker = OfflineTracker()
            val (place, current) = resolveByCoordinates(latitude, longitude, tracker)
            snapshot(place, tracker) { current }
        }

    override suspend fun towns(county: String): List<String> = withContext(Dispatchers.IO) {
        val dataset = Counties.threeDayDataset(county) ?: return@withContext emptyList()
        parseTownNames(source.fetch(dataset, mapOf("ElementName" to "溫度"), OfflineTracker()))
    }

    /**
     * [current] loads alongside the forecasts instead of before them. It is handed the town's
     * position, which comes from the 3-day forecast response and is only awaited if it is needed.
     *
     * The observation and the two forecasts fail separately, so what did load is still shown. The
     * load as a whole fails only when all three do, or when CWA rejects the key.
     */
    private suspend fun snapshot(
        place: Place,
        tracker: OfflineTracker,
        current: suspend (townCoordinates: Deferred<Coordinates?>) -> CurrentWeather?,
    ) =
        coroutineScope {
            val threeDay = async { attempt { threeDayForecast(place, tracker) } }
            val townCoordinates = async {
                threeDay.await().getOrNull()?.let { parseTownCoordinates(it, place.town) }
            }
            val observation = async { attempt { current(townCoordinates) } }
            val week = async { attempt { daily(place, tracker) } }
            val alerts = async { alerts(place.county, tracker) }

            val currentResult = observation.await()
            val hourlyResult = threeDay.await().map { root ->
                root?.let { parseHourly(it, place.town, now()) }.orEmpty()
            }
            val dailyResult = week.await()
            val results = listOf(currentResult, hourlyResult, dailyResult)
            if (results.all { it.isFailure }) throw checkNotNull(results.first().exceptionOrNull())

            WeatherSnapshot(
                place = place,
                current = currentResult.getOrNull(),
                hourly = hourlyResult.getOrNull(),
                daily = dailyResult.getOrNull(),
                alerts = alerts.await(),
                offlineSince = tracker.offlineSince,
                currentUnavailable = currentResult.isFailure,
            )
        }

    /** Turns a failure into a result, except a rejected key, which has to reach the screen. */
    private suspend fun <T> attempt(load: suspend () -> T): Result<T> = try {
        Result.success(load())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e is HttpException && (e.code() == 401 || e.code() == 403)) throw e
        Result.failure(e)
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

    /**
     * Prefers a synoptic station in the town, then any station there. A town with no station gets
     * the one closest to its centre, in whichever county that is, with the distance filled in.
     */
    private suspend fun currentForPlace(
        place: Place,
        tracker: OfflineTracker,
        townCoordinates: Deferred<Coordinates?>,
    ): CurrentWeather? {
        // The larger list is only requested if the synoptic stations do not cover the town.
        val synoptic = stations(SYNOPTIC_DATASET, tracker)
        synoptic.firstOrNull { it.inTown(place) }?.let { return CurrentWeather(it, null) }
        val all = stations(ALL_STATIONS_DATASET, tracker)
        all.firstOrNull { it.inTown(place) }?.let { return CurrentWeather(it, null) }

        val centre = townCoordinates.await()
        if (centre != null) {
            nearestStation(centre, synoptic + all)?.let { (station, distance) ->
                return CurrentWeather(station, distance)
            }
        }
        // Without the town's position, a station in the same county is the best guess left.
        val station = synoptic.firstOrNull { it.county == place.county }
            ?: all.firstOrNull { it.county == place.county }
        return station?.let { CurrentWeather(it, null) }
    }

    private suspend fun threeDayForecast(place: Place, tracker: OfflineTracker): JsonObject? {
        val dataset = Counties.threeDayDataset(place.county) ?: return null
        return source.fetch(
            dataset,
            mapOf("LocationName" to place.town, "ElementName" to "溫度,天氣現象,3小時降雨機率", "sort" to "time"),
            tracker,
        )
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
        return parseDaily(root, place.town, now())
    }

    /** A failure here returns null so the forecast can still be shown. */
    private suspend fun alerts(county: String, tracker: OfflineTracker): List<WeatherAlert>? = try {
        parseAlerts(
            byCounty = source.fetch("W-C0033-001", tracker = tracker),
            details = source.fetch("W-C0033-002", tracker = tracker),
            county = county,
            now = now(),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun Station.inTown(place: Place) = county == place.county && town == place.town

    companion object {
        private const val SYNOPTIC_DATASET = "O-A0003-001"
        private const val ALL_STATIONS_DATASET = "O-A0001-001"

        fun create(context: Context, credentials: CredentialStore) = WeatherRepository(
            source = CachedSource(CwaApi.create { credentials.apiKey }, File(context.filesDir, "api-cache")),
            verify = { candidate -> CwaApi.create { candidate }.datastore("W-C0033-001") },
        )
    }
}
