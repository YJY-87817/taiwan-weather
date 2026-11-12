package tw.personal.cwaweather.data

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

private fun JsonElement?.arr(key: String): List<JsonElement> =
    ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()

private fun JsonElement?.text(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.content?.takeUnless { isMissing(it) }

private fun JsonElement?.number(key: String): Double? = text(key)?.toDoubleOrNull()

/** CWA marks unavailable values with negative sentinels such as -99, or with "-" and an empty string. */
private fun isMissing(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed == "-" || trimmed == "X") return true
    val number = trimmed.toDoubleOrNull() ?: return false
    return number <= -90
}

private fun parseTime(value: String?): OffsetDateTime? =
    value?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

private val alertTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val taiwanOffset = ZoneOffset.ofHours(8)

/** Alert times have no offset in the response and are Taiwan local time. */
private fun parseAlertTime(value: String?): OffsetDateTime? = value?.let {
    runCatching { LocalDateTime.parse(it, alertTimeFormat).atOffset(taiwanOffset) }.getOrNull()
}

/** Accepts either a JSON array or a single object under the key. */
private fun JsonElement?.list(key: String): List<JsonElement> =
    when (val value = (this as? JsonObject)?.get(key)) {
        is JsonArray -> value
        is JsonObject -> listOf(value)
        else -> emptyList()
    }

/** Returns the county's alerts that have not ended, joined with their full text. */
fun parseAlerts(
    byCounty: JsonObject,
    details: JsonObject,
    county: String,
    now: OffsetDateTime,
): List<WeatherAlert> {
    val records = details.obj("records").list("record")

    fun JsonElement.hazards(phenomena: String) = obj("hazardConditions").obj("hazards").list("hazard")
        .map { it.obj("info") }
        .filter { it.text("phenomena") == phenomena }

    fun JsonElement?.names(county: String) =
        obj("affectedAreas").list("location").any { it.text("locationName") == county }

    // Two bulletins can cover the same phenomenon, so the one naming this county wins. Areas are not
    // always counties (for example 恆春半島), so a bulletin for the phenomenon is still better than none.
    fun detailFor(phenomena: String): String? {
        val record = records.firstOrNull { record -> record.hazards(phenomena).any { it.names(county) } }
            ?: records.firstOrNull { record -> record.hazards(phenomena).isNotEmpty() }
        return record?.obj("contents")?.obj("content")?.text("contentText")?.trim()
    }

    return byCounty.obj("records").list("location")
        .firstOrNull { it.text("locationName") == county }
        .obj("hazardConditions")
        .list("hazards")
        .mapNotNull { hazard ->
            val info = hazard.obj("info")
            val phenomena = info.text("phenomena") ?: return@mapNotNull null
            val end = parseAlertTime(hazard.obj("validTime").text("endTime"))
            if (end != null && !end.isAfter(now)) return@mapNotNull null
            WeatherAlert(
                title = phenomena + info.text("significance").orEmpty(),
                start = parseAlertTime(hazard.obj("validTime").text("startTime")),
                end = end,
                detail = detailFor(phenomena),
            )
        }
}

fun parseStations(root: JsonObject): List<Station> =
    root.obj("records").arr("Station").mapNotNull { item ->
        val geo = item.obj("GeoInfo")
        val coordinate = geo.arr("Coordinates").firstOrNull { it.text("CoordinateName") == "WGS84" }
            ?: geo.arr("Coordinates").firstOrNull()
        val latitude = coordinate.number("StationLatitude") ?: return@mapNotNull null
        val longitude = coordinate.number("StationLongitude") ?: return@mapNotNull null
        val element = item.obj("WeatherElement")
        val extreme = element.obj("DailyExtreme")
        Station(
            id = item.text("StationId").orEmpty(),
            name = item.text("StationName").orEmpty(),
            county = geo.text("CountyName").orEmpty(),
            town = geo.text("TownName").orEmpty(),
            latitude = latitude,
            longitude = longitude,
            observedAt = parseTime(item.obj("ObsTime").text("DateTime")),
            temperature = element.number("AirTemperature"),
            humidity = element.number("RelativeHumidity"),
            weather = element.text("Weather"),
            windSpeed = element.number("WindSpeed"),
            windDirection = element.number("WindDirection"),
            pressure = element.number("AirPressure"),
            uvIndex = element.number("UVIndex"),
            precipitation = element.obj("Now").number("Precipitation"),
            dailyHigh = extreme.obj("DailyHigh").obj("TemperatureInfo").number("AirTemperature"),
            dailyLow = extreme.obj("DailyLow").obj("TemperatureInfo").number("AirTemperature"),
            altitude = geo.number("StationAltitude"),
        )
    }

private class ForecastPoint(
    val start: OffsetDateTime,
    val end: OffsetDateTime?,
    val value: JsonElement?,
)

private fun forecastLocations(root: JsonObject): List<JsonElement> =
    root.obj("records").arr("Locations").firstOrNull().arr("Location")

fun parseTownNames(root: JsonObject): List<String> =
    forecastLocations(root).mapNotNull { it.text("LocationName") }

/** The town's position as given in a forecast response, or null if the town or its position is absent. */
fun parseTownCoordinates(root: JsonObject, town: String): Coordinates? {
    val location = townLocation(root, town)
    val latitude = location.number("Latitude") ?: return null
    val longitude = location.number("Longitude") ?: return null
    return Coordinates(latitude, longitude)
}

private fun elementPoints(location: JsonElement?, elementName: String): List<ForecastPoint> =
    location.arr("WeatherElement")
        .firstOrNull { it.text("ElementName") == elementName }
        .arr("Time")
        .mapNotNull { time ->
            val start = parseTime(time.text("DataTime") ?: time.text("StartTime")) ?: return@mapNotNull null
            ForecastPoint(start, parseTime(time.text("EndTime")), time.arr("ElementValue").firstOrNull())
        }

private fun townLocation(root: JsonObject, town: String): JsonElement? =
    forecastLocations(root).firstOrNull { it.text("LocationName") == town }

fun parseHourly(root: JsonObject, town: String, now: OffsetDateTime): List<HourlyForecast> {
    val location = townLocation(root, town)
    val weather = elementPoints(location, "天氣現象")
    val rain = elementPoints(location, "3小時降雨機率")

    fun covering(points: List<ForecastPoint>, time: OffsetDateTime) = points.firstOrNull {
        !time.isBefore(it.start) && it.end?.let(time::isBefore) == true
    }

    return elementPoints(location, "溫度").mapNotNull { point ->
        val temperature = point.value.number("Temperature") ?: return@mapNotNull null
        if (point.start.isBefore(now.minusHours(1))) return@mapNotNull null
        HourlyForecast(
            time = point.start,
            temperature = temperature,
            weather = covering(weather, point.start)?.value.text("Weather"),
            rainChance = covering(rain, point.start)?.value.number("ProbabilityOfPrecipitation")?.toInt(),
        )
    }
}

fun parseDaily(root: JsonObject, town: String, now: OffsetDateTime): List<DailyForecast> {
    val location = townLocation(root, town)
    val highs = elementPoints(location, "最高溫度")
    val lows = elementPoints(location, "最低溫度")
    val weather = elementPoints(location, "天氣現象")
    val rain = elementPoints(location, "12小時降雨機率")

    // Dates are Taiwan calendar days whatever the phone's time zone is.
    fun ForecastPoint.taiwanDate() = start.withOffsetSameInstant(taiwanOffset).toLocalDate()
    val today = now.withOffsetSameInstant(taiwanOffset).toLocalDate()
    val dates = (highs + lows + weather).map { it.taiwanDate() }.distinct().sorted()
        .filterNot { it.isBefore(today) }

    return dates.map { date ->
        fun onDate(points: List<ForecastPoint>) = points.filter { it.taiwanDate() == date }
        // Prefer the daytime period's description so each row reads as that day's weather.
        val dayWeather = onDate(weather).let { periods ->
            periods.firstOrNull { it.start.hour in 6..11 } ?: periods.firstOrNull()
        }
        DailyForecast(
            date = date,
            high = onDate(highs).mapNotNull { it.value.number("MaxTemperature") }.maxOrNull(),
            low = onDate(lows).mapNotNull { it.value.number("MinTemperature") }.minOrNull(),
            weather = dayWeather?.value.text("Weather"),
            rainChance = onDate(rain)
                .mapNotNull { it.value.number("ProbabilityOfPrecipitation")?.toInt() }
                .maxOrNull(),
        )
    }
}
