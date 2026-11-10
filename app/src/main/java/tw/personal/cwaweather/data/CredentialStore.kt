package tw.personal.cwaweather.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Holds the user's CWA authorization code in app-private storage, excluded from backups. */
class CredentialStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("credentials", Context.MODE_PRIVATE))

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, null).orEmpty()
        set(value) {
            prefs.edit { putString(KEY_API_KEY, value) }
        }

    private companion object {
        const val KEY_API_KEY = "cwa_api_key"
    }
}
