package tw.personal.cwaweather.data

class OutsideTaiwanException : Exception()
class LocationUnavailableException : Exception()

data class Coordinates(val latitude: Double, val longitude: Double)

/** What the screen state holder needs from the data layer. */
interface WeatherSource {
    /** Throws if CWA rejects the key or cannot be reached. Never answered from the cache. */
    suspend fun verifyKey(candidate: String)

    /** With [refresh], every request goes to CWA even if a copy was saved moments ago. */
    suspend fun loadForPlace(place: Place, refresh: Boolean = false): WeatherSnapshot

    /** With [refresh], every request goes to CWA even if a copy was saved moments ago. */
    suspend fun loadAtCoordinates(latitude: Double, longitude: Double, refresh: Boolean = false): WeatherSnapshot

    suspend fun towns(county: String): List<String>
}

fun interface LocationProvider {
    /** Callers must already hold ACCESS_COARSE_LOCATION. Throws [LocationUnavailableException] if none is known. */
    suspend fun current(): Coordinates
}
