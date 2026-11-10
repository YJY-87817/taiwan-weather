package tw.personal.cwaweather.data

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val PREFERRED_SYNOPTIC_RANGE_KM = 15.0
const val MAX_DISTANCE_FROM_STATION_KM = 100.0

fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/**
 * The dense station list decides the town; the synoptic list is preferred for observations when
 * one is within [PREFERRED_SYNOPTIC_RANGE_KM].
 */
fun resolveNearest(
    latitude: Double,
    longitude: Double,
    synoptic: List<Station>,
    all: List<Station>,
): Pair<Place, CurrentWeather> {
    fun List<Station>.nearest() = map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
        .minByOrNull { it.second }

    val closest = (synoptic + all).filter { it.town.isNotBlank() }.nearest()
        ?: throw LocationUnavailableException()
    if (closest.second > MAX_DISTANCE_FROM_STATION_KM) throw OutsideTaiwanException()

    val closestSynoptic = synoptic.nearest()
    val observation = closestSynoptic?.takeIf { it.second <= PREFERRED_SYNOPTIC_RANGE_KM } ?: closest
    return Place(closest.first.county, closest.first.town) to CurrentWeather(observation.first, observation.second)
}
