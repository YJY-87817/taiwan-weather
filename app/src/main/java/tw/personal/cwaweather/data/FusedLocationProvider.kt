package tw.personal.cwaweather.data

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

class FusedLocationProvider(private val context: Context) : LocationProvider {
    @SuppressLint("MissingPermission")
    override suspend fun current(): Coordinates {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            ?: client.lastLocation.await()
            ?: throw LocationUnavailableException()
        return Coordinates(location.latitude, location.longitude)
    }
}
