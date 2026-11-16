package tw.personal.cwaweather.data

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** A position and when it was measured, in epoch millis. */
data class LocationFix(val latitude: Double, val longitude: Double, val timeMillis: Long)

/**
 * Waits up to [timeoutMillis] for a [fresh] fix. Without one, takes the [lastKnown] fix if it is
 * no more than [maxAgeMillis] old, since an older one may be from another town entirely.
 * Throws [LocationUnavailableException] when neither will do.
 */
internal suspend fun locate(
    fresh: suspend () -> LocationFix?,
    lastKnown: suspend () -> LocationFix?,
    nowMillis: () -> Long,
    timeoutMillis: Long,
    maxAgeMillis: Long,
): Coordinates {
    val fix = withTimeoutOrNull(timeoutMillis) { fresh() }
        ?: lastKnown()?.takeIf { nowMillis() - it.timeMillis <= maxAgeMillis }
        ?: throw LocationUnavailableException()
    return Coordinates(fix.latitude, fix.longitude)
}

class FusedLocationProvider(private val context: Context) : LocationProvider {
    @SuppressLint("MissingPermission")
    override suspend fun current(): Coordinates {
        val client = LocationServices.getFusedLocationProviderClient(context)
        return try {
            locate(
                fresh = {
                    // The token stops the request when the wait is cancelled or runs out of time.
                    val cancellation = CancellationTokenSource()
                    try {
                        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
                            .await()?.let { LocationFix(it.latitude, it.longitude, it.time) }
                    } finally {
                        cancellation.cancel()
                    }
                },
                lastKnown = { client.lastLocation.await()?.let { LocationFix(it.latitude, it.longitude, it.time) } },
                nowMillis = System::currentTimeMillis,
                timeoutMillis = CURRENT_LOCATION_TIMEOUT_MILLIS,
                maxAgeMillis = LAST_LOCATION_MAX_AGE_MILLIS,
            )
        } catch (e: ApiException) {
            // For example a phone without Google Play services.
            throw LocationUnavailableException()
        }
    }

    private companion object {
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 15_000L
        const val LAST_LOCATION_MAX_AGE_MILLIS = 30L * 60 * 1000
    }
}
