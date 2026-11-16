package tw.personal.cwaweather.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Keeps each town's position once a forecast has given it, with no expiry, since towns do not
 * move. It stands in when the forecast cannot be read, so a town without its own station can
 * still be matched to the station nearest to it.
 */
class TownPositionStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("town_positions", Context.MODE_PRIVATE))

    operator fun get(place: Place): Coordinates? {
        val parts = prefs.getString(key(place), null)?.split(SEPARATOR) ?: return null
        val latitude = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val longitude = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return Coordinates(latitude, longitude)
    }

    operator fun set(place: Place, position: Coordinates) {
        val value = "${position.latitude}$SEPARATOR${position.longitude}"
        if (prefs.getString(key(place), null) != value) prefs.edit { putString(key(place), value) }
    }

    private fun key(place: Place) = "${place.county}|${place.town}"

    private companion object {
        const val SEPARATOR = ","
    }
}
