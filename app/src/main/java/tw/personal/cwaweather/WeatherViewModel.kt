package tw.personal.cwaweather

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import tw.personal.cwaweather.data.CredentialStore
import tw.personal.cwaweather.data.FusedLocationProvider
import tw.personal.cwaweather.data.LocationProvider
import tw.personal.cwaweather.data.LocationUnavailableException
import tw.personal.cwaweather.data.OutsideTaiwanException
import tw.personal.cwaweather.data.Place
import tw.personal.cwaweather.data.PlaceStore
import tw.personal.cwaweather.data.WeatherRepository
import tw.personal.cwaweather.data.WeatherSnapshot
import tw.personal.cwaweather.data.WeatherSource

data class WeatherUiState(
    val loading: Boolean = false,
    val place: Place? = null,
    val usesDeviceLocation: Boolean = false,
    val snapshot: WeatherSnapshot? = null,
    val favorites: List<Place> = emptyList(),
    val message: String? = null,
    val askForKey: Boolean = false,
    val hasApiKey: Boolean = false,
    val verifyingKey: Boolean = false,
    val keyError: String? = null,
    val showPicker: Boolean = false,
    /** The activity owes a first load: on a new ViewModel, and again after a newly saved key. */
    val startPending: Boolean = true,
)

class WeatherViewModel(
    private val credentials: CredentialStore,
    private val repository: WeatherSource,
    private val places: PlaceStore,
    private val locationProvider: LocationProvider,
) : ViewModel() {
    private val state = MutableStateFlow(
        WeatherUiState(favorites = places.favorites, hasApiKey = credentials.apiKey.isNotBlank()),
    )
    private var loadJob: Job? = null

    /** What the screen showed after the last load that worked, to go back to when a later one fails. */
    private var lastLoaded: WeatherUiState? = null

    val uiState: StateFlow<WeatherUiState> = state

    /**
     * True the first time it is called on this ViewModel, and again after each newly saved key.
     * A recreated activity keeps the ViewModel and must not reload, but a new process (after the
     * system killed the old one) gets a new ViewModel and still needs its first load.
     */
    fun claimStart(): Boolean = state.getAndUpdate { it.copy(startPending = false) }.startPending

    fun hasApiKey(): Boolean = credentials.apiKey.isNotBlank()

    fun setAskForKey(ask: Boolean) = state.update { it.copy(askForKey = ask, keyError = null) }

    fun clearKeyError() = state.update { it.copy(keyError = null) }

    fun setShowPicker(show: Boolean) = state.update { it.copy(showPicker = show) }

    /**
     * Saves the key only after CWA accepts it. The check outlives a recreated activity, and its
     * outcome is reported through [WeatherUiState.verifyingKey] and [WeatherUiState.keyError].
     * A saved key sets [WeatherUiState.startPending] so the activity loads with it.
     */
    fun saveApiKey(candidate: String) {
        if (state.value.verifyingKey) return
        state.update { it.copy(verifyingKey = true, keyError = null) }
        viewModelScope.launch {
            val error = verifyKey(candidate)
            if (error != null) {
                state.update { it.copy(verifyingKey = false, keyError = error) }
                return@launch
            }
            credentials.apiKey = candidate
            state.update {
                it.copy(
                    verifyingKey = false,
                    askForKey = false,
                    hasApiKey = true,
                    message = null,
                    startPending = true,
                )
            }
        }
    }

    /** Returns an error message, or null when CWA accepts the key. */
    private suspend fun verifyKey(candidate: String): String? = try {
        repository.verifyKey(candidate)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpException) {
        if (e.code() == 401 || e.code() == 403) {
            "氣象署不接受這組授權碼，請確認後再輸入。"
        } else {
            "氣象署服務回應錯誤（HTTP ${e.code()}），請稍後再試。"
        }
    } catch (e: IOException) {
        "無法連線到氣象署，暫時無法確認授權碼，請確認網路後再試。"
    } catch (e: Exception) {
        // For example a reply that is not the expected JSON. It must not crash the app.
        "氣象署回應的內容無法讀取（${e.javaClass.simpleName}），請稍後再試。"
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
            val location = locationProvider.current()
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
                lastLoaded = state.value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Go back to what was on screen, so the title, the selected chip and what "refresh"
                // reloads all agree with the data shown. With nothing loaded yet, the attempted
                // place stays so that it can be retried.
                lastLoaded?.let { loaded ->
                    state.update {
                        it.copy(
                            place = loaded.place,
                            usesDeviceLocation = loaded.usesDeviceLocation,
                            snapshot = loaded.snapshot,
                        )
                    }
                }
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

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[APPLICATION_KEY]) as Application
                val credentials = CredentialStore(application)
                WeatherViewModel(
                    credentials = credentials,
                    repository = WeatherRepository.create(application, credentials),
                    places = PlaceStore(application),
                    locationProvider = FusedLocationProvider(application),
                )
            }
        }
    }
}
