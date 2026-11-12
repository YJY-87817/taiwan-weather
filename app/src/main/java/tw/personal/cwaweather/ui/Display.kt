package tw.personal.cwaweather.ui

import java.time.OffsetDateTime
import kotlin.math.roundToInt
import tw.personal.cwaweather.data.Place
import tw.personal.cwaweather.data.Station
import tw.personal.cwaweather.data.TAIWAN_OFFSET
import tw.personal.cwaweather.data.inTaiwan

/** A favorite's chip text: the town, with its county in front when another favorite shares the town's name. */
internal fun favoriteLabel(place: Place, favorites: List<Place>): String =
    if (favorites.any { it != place && it.town == place.town }) "${place.county}${place.town}" else place.town

/** No wind has no direction, whatever direction value the station reports with it. */
internal fun windDirectionText(speed: Double?, degrees: Double?): String {
    if (speed == 0.0) return "靜風"
    if (degrees == null) return "—"
    val names = listOf("北", "東北", "東", "東南", "南", "西南", "西", "西北")
    return names[((degrees / 45).roundToInt() % 8 + 8) % 8]
}

internal fun rainText(station: Station): String = when {
    station.precipitationTrace -> "微量"
    station.precipitation != null -> "%.1f mm".format(station.precipitation)
    else -> "—"
}

/** Places forecast times along a chart by how far apart they really are, since the steps are uneven. */
internal class ChartScale(times: List<OffsetDateTime>) {
    private val first = times.min()
    private val last = times.max()
    private val start = first.toEpochSecond()
    private val span = (last.toEpochSecond() - start).coerceAtLeast(1)

    /** 0 at the earliest time and 1 at the latest. */
    fun fraction(time: OffsetDateTime): Float =
        ((time.toEpochSecond() - start).toFloat() / span).coerceIn(0f, 1f)

    /** The Taiwan midnights after the earliest time and up to the latest, where each new day begins. */
    val dayStarts: List<OffsetDateTime> =
        generateSequence(first.inTaiwan().toLocalDate().plusDays(1)) { it.plusDays(1) }
            .map { it.atStartOfDay().atOffset(TAIWAN_OFFSET) }
            .takeWhile { !it.isAfter(last) }
            .toList()
}
