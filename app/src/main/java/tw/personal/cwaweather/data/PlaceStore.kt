package tw.personal.cwaweather.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Keeps the manually chosen place and the favorites, in the order they were added. */
class PlaceStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    var selected: Place?
        get() {
            val county = prefs.getString(KEY_COUNTY, null) ?: return null
            val town = prefs.getString(KEY_TOWN, null) ?: return null
            return Place(county, town)
        }
        set(value) {
            prefs.edit {
                putString(KEY_COUNTY, value?.county)
                putString(KEY_TOWN, value?.town)
            }
        }

    var favorites: List<Place>
        get() = prefs.getString(KEY_FAVORITES, null).orEmpty().lines().mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size == 2) Place(parts[0], parts[1]) else null
        }
        set(value) {
            val text = value.joinToString("\n") { "${it.county}$SEPARATOR${it.town}" }
            prefs.edit { putString(KEY_FAVORITES, text) }
        }

    private companion object {
        const val KEY_COUNTY = "county"
        const val KEY_TOWN = "town"
        const val KEY_FAVORITES = "favorites"
        const val SEPARATOR = "|"
    }
}
