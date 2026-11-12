package tw.personal.cwaweather.data

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class FusedLocationProvider(private val context: Context) : LocationProvider {
    @SuppressLint("MissingPermission")
    override suspend fun current(): Coordinates {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = try {
            // The token stops the request when the wait is cancelled or runs out of time.
            val cancellation = CancellationTokenSource()
            val fresh = try {
                withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MILLIS) {
                    client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
                        .await()
                }
            } finally {
                cancellation.cancel()
            }
            fresh ?: client.lastLocation.await()?.takeIf {
                System.currentTimeMillis() - it.time <= LAST_LOCATION_MAX_AGE_MILLIS
            }
        } catch (e: ApiException) {
            // For example a phone without Google Play services.
            throw LocationUnavailableException()
        }
        if (location == null) throw LocationUnavailableException()
        return Coordinates(location.latitude, location.longitude)
    }

    private companion object {
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 15_000L

        // An older fix may be from somewhere else entirely, and would show the wrong town's weather.
        const val LAST_LOCATION_MAX_AGE_MILLIS = 30L * 60 * 1000
    }
}
