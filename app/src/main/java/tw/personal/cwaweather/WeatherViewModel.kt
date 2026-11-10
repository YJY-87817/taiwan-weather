package tw.personal.cwaweather

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import tw.personal.cwaweather.data.CredentialStore
import tw.personal.cwaweather.data.LocationUnavailableException
import tw.personal.cwaweather.data.OutsideTaiwanException
import tw.personal.cwaweather.data.Place
import tw.personal.cwaweather.data.PlaceStore
import tw.personal.cwaweather.data.WeatherRepository
import tw.personal.cwaweather.data.WeatherSnapshot

data class WeatherUiState(
    val loading: Boolean = false,
    val place: Place? = null,
    val usesDeviceLocation: Boolean = false,
    val snapshot: WeatherSnapshot? = null,
    val favorites: List<Place> = emptyList(),
    val message: String? = null,
    val askForKey: Boolean = false,
)

class WeatherViewModel(application: Application) : AndroidViewModel(application) {
    private val credentials = CredentialStore(application)
    private val repository = WeatherRepository(application, credentials)
    private val places = PlaceStore(application)
    private val state = MutableStateFlow(WeatherUiState(favorites = places.favorites))
    private var loadJob: Job? = null
    private var started = false

    val uiState: StateFlow<WeatherUiState> = state

    /**
     * True only the first time it is called on this ViewModel. A recreated activity keeps the
     * ViewModel and must not reload, but a new process (after the system killed the old one)
     * gets a new ViewModel and still needs its first load.
     */
    fun claimStart(): Boolean = !started.also { started = true }

    fun hasApiKey(): Boolean = credentials.apiKey.isNotBlank()

    fun setAskForKey(ask: Boolean) = state.update { it.copy(askForKey = ask) }

    /** Saves the key only after CWA accepts it. Returns an error message, or null on success. */
    suspend fun saveApiKey(candidate: String): String? {
        try {
            repository.verifyKey(candidate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            return if (e.code() == 401 || e.code() == 403) {
                "氣象署不接受這組授權碼，請確認後再輸入。"
            } else {
                "氣象署服務回應錯誤（HTTP ${e.code()}），請稍後再試。"
            }
        } catch (e: IOException) {
            return "無法連線到氣象署，暫時無法確認授權碼，請確認網路後再試。"
        }
        credentials.apiKey = candidate
        state.update { it.copy(askForKey = false, message = null) }
        return null
    }

    fun savedPlace(): Place? = places.selected

    fun showMessage(message: String) = state.update { it.copy(loading = false, message = message) }

    fun refresh() {
        val current = state.value
        when {
            current.usesDeviceLocation -> loadDeviceLocation()
            current.place != null -> selectPlace(current.place)
        }
    }

    fun selectPlace(place: Place) {
        launchLoad(place, usesDeviceLocation = false) {
            // Remembered only once it loads, so a failure keeps the previous choice.
            repository.loadForPlace(place).also { places.selected = place }
        }
    }

    fun loadDeviceLocation() {
        launchLoad(state.value.place, usesDeviceLocation = true) {
            val location = repository.deviceLocation()
            // Forget the chosen place only after the device location worked.
            repository.loadAtCoordinates(location.latitude, location.longitude)
                .also { places.selected = null }
        }
    }

    fun toggleFavorite(place: Place) {
        val favorites = places.favorites
        val updated = if (place in favorites) favorites - place else favorites + place
        places.favorites = updated
        state.update { it.copy(favorites = updated) }
    }

    suspend fun towns(county: String): Result<List<String>> = try {
        Result.success(repository.towns(county))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun launchLoad(
        shownPlace: Place?,
        usesDeviceLocation: Boolean,
        load: suspend () -> WeatherSnapshot,
    ) {
        loadJob?.cancel()
        state.update {
            it.copy(
                loading = true,
                place = shownPlace,
                usesDeviceLocation = usesDeviceLocation,
                // Another place's data must not stay on screen under the new title.
                snapshot = it.snapshot.takeIf { old -> old?.place == shownPlace },
                message = null,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val snapshot = load()
                state.update { it.copy(loading = false, place = snapshot.place, snapshot = snapshot) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(describe(e))
                if (e is HttpException && (e.code() == 401 || e.code() == 403)) setAskForKey(true)
            }
        }
    }

    private fun describe(error: Exception): String = when (error) {
        is OutsideTaiwanException -> "目前位置不在臺灣測站範圍內，請手動選擇地點。"
        is LocationUnavailableException -> "暫時無法取得位置，請確認已開啟定位，或手動選擇地點。"
        is HttpException -> when (error.code()) {
            401, 403 -> "授權碼無效或已失效，請重新輸入。"
            else -> "氣象署服務回應錯誤（HTTP ${error.code()}），請稍後再試。"
        }
        is IOException -> "無法連線到氣象署，這個地點也沒有可用的已儲存資料。"
        else -> "資料讀取失敗（${error.javaClass.simpleName}），請稍後再試。"
    }
}
