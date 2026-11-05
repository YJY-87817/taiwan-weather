package tw.personal.cwaweather.data

import java.time.LocalDate
import java.time.OffsetDateTime

data class Place(val county: String, val town: String)

data class Station(
    val id: String,
    val name: String,
    val county: String,
    val town: String,
    val latitude: Double,
    val longitude: Double,
    val observedAt: OffsetDateTime?,
    val temperature: Double?,
    val humidity: Double?,
    val weather: String?,
    val windSpeed: Double?,
    val windDirection: Double?,
    val pressure: Double?,
    val uvIndex: Double?,
    val precipitation: Double?,
    val dailyHigh: Double?,
    val dailyLow: Double?,
)

data class CurrentWeather(val station: Station, val distanceKm: Double?)

data class HourlyForecast(
    val time: OffsetDateTime,
    val temperature: Double,
    val weather: String?,
    val rainChance: Int?,
)

data class DailyForecast(
    val date: LocalDate,
    val high: Double?,
    val low: Double?,
    val weather: String?,
    val rainChance: Int?,
)

data class WeatherAlert(
    val title: String,
    val start: OffsetDateTime?,
    val end: OffsetDateTime?,
    val detail: String?,
)

data class WeatherSnapshot(
    val place: Place,
    val current: CurrentWeather?,
    val hourly: List<HourlyForecast>,
    val daily: List<DailyForecast>,
    /** Null means the alert data could not be read, which is different from having no alerts. */
    val alerts: List<WeatherAlert>?,
    /** Epoch millis of the oldest cached response used, or null when everything is live. */
    val offlineSince: Long?,
)

/** County-to-dataset IDs from the CWA API documentation. A county's weekly ID is its 3-day ID + 2. */
object Counties {
    private val threeDayNumber = linkedMapOf(
        "基隆市" to 49, "臺北市" to 61, "新北市" to 69, "桃園市" to 5, "新竹市" to 53, "新竹縣" to 9,
        "苗栗縣" to 13, "臺中市" to 73, "彰化縣" to 17, "南投縣" to 21, "雲林縣" to 25, "嘉義市" to 57,
        "嘉義縣" to 29, "臺南市" to 77, "高雄市" to 65, "屏東縣" to 33, "宜蘭縣" to 1, "花蓮縣" to 41,
        "臺東縣" to 37, "澎湖縣" to 45, "金門縣" to 85, "連江縣" to 81,
    )

    val names: List<String> = threeDayNumber.keys.toList()

    fun threeDayDataset(county: String): String? = threeDayNumber[county]?.let(::datasetId)

    fun weeklyDataset(county: String): String? = threeDayNumber[county]?.let { datasetId(it + 2) }

    private fun datasetId(number: Int) = "F-D0047-%03d".format(number)
}
