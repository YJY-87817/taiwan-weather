package tw.personal.cwaweather

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import tw.personal.cwaweather.data.Coordinates
import tw.personal.cwaweather.data.CredentialStore
import tw.personal.cwaweather.data.FakeSharedPreferences
import tw.personal.cwaweather.data.LocationProvider
import tw.personal.cwaweather.data.LocationUnavailableException
import tw.personal.cwaweather.data.OutsideTaiwanException
import tw.personal.cwaweather.data.Place
import tw.personal.cwaweather.data.PlaceStore
import tw.personal.cwaweather.data.WeatherSnapshot
import tw.personal.cwaweather.data.WeatherSource

@OptIn(ExperimentalCoroutinesApi::class)
class WeatherViewModelTest {
    private val taipei = Place("臺北市", "中正區")
    private val kaohsiung = Place("高雄市", "左營區")

    private fun snapshot(place: Place) = WeatherSnapshot(place, null, emptyList(), emptyList(), emptyList(), null)

    private fun httpError(code: Int) =
        HttpException(Response.error<JsonObject>(code, "".toResponseBody(null)))

    private class FakeSource : WeatherSource {
        var verify: suspend (String) -> Unit = {}
        var forPlace: suspend (Place) -> WeatherSnapshot = { error("not set") }
        var atCoordinates: suspend (Double, Double) -> WeatherSnapshot = { _, _ -> error("not set") }
        var townsOf: suspend (String) -> List<String> = { emptyList() }
        val placeLoads = mutableListOf<Place>()
        val coordinateLoads = mutableListOf<Coordinates>()

        override suspend fun verifyKey(candidate: String) = verify(candidate)
        override suspend fun loadForPlace(place: Place): WeatherSnapshot {
            placeLoads += place
            return forPlace(place)
        }
        override suspend fun loadAtCoordinates(latitude: Double, longitude: Double): WeatherSnapshot {
            coordinateLoads += Coordinates(latitude, longitude)
            return atCoordinates(latitude, longitude)
        }
        override suspend fun towns(county: String) = townsOf(county)
    }

    private val source = FakeSource()
    private val credentialPrefs = FakeSharedPreferences()
    private val placePrefs = FakeSharedPreferences()
    private val credentials = CredentialStore(credentialPrefs)
    private val places = PlaceStore(placePrefs)
    private var location: suspend () -> Coordinates = { Coordinates(25.04, 121.52) }

    private fun viewModel() = WeatherViewModel(credentials, source, places, LocationProvider { location() })

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startIsClaimedOnlyOnce() {
        val viewModel = viewModel()
        assertTrue(viewModel.claimStart())
        assertFalse(viewModel.claimStart())
    }

    @Test
    fun apiKeyPresenceFollowsTheStore() {
        val viewModel = viewModel()
        assertFalse(viewModel.hasApiKey())
        credentials.apiKey = "  "
        assertFalse(viewModel.hasApiKey())
        credentials.apiKey = "KEY"
        assertTrue(viewModel.hasApiKey())
    }

    @Test
    fun selectingAPlaceShowsItsSnapshotAndRemembersIt() {
        source.forPlace = { snapshot(it) }
        val viewModel = viewModel()
        viewModel.selectPlace(taipei)

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(taipei, state.place)
        assertEquals(taipei, state.snapshot?.place)
        assertFalse(state.usesDeviceLocation)
        assertNull(state.message)
        assertEquals(taipei, places.selected)
    }

    @Test
    fun failedLoadKeepsThePreviouslyRememberedPlace() {
        places.selected = taipei
        source.forPlace = { throw IOException("down") }
        val viewModel = viewModel()
        viewModel.selectPlace(kaohsiung)

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertNotNull(state.message)
        assertTrue(state.message!!.contains("無法連線"))
        assertEquals(taipei, places.selected)
    }

    @Test
    fun anotherPlacesSnapshotIsNotShownWhileLoading() {
        val gate = CompletableDeferred<WeatherSnapshot>()
        source.forPlace = { place -> if (place == kaohsiung) gate.await() else snapshot(place) }
        val viewModel = viewModel()
        viewModel.selectPlace(taipei)
        assertEquals(taipei, viewModel.uiState.value.snapshot?.place)

        viewModel.selectPlace(kaohsiung)
        assertTrue(viewModel.uiState.value.loading)
        assertNull(viewModel.uiState.value.snapshot)

        gate.complete(snapshot(kaohsiung))
        assertEquals(kaohsiung, viewModel.uiState.value.snapshot?.place)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun refreshingTheSamePlaceKeepsItsSnapshotOnScreen() {
        val gate = CompletableDeferred<WeatherSnapshot>()
        var first = true
        source.forPlace = { place -> if (first) snapshot(place).also { first = false } else gate.await() }
        val viewModel = viewModel()
        viewModel.selectPlace(taipei)

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.loading)
        assertEquals(taipei, viewModel.uiState.value.snapshot?.place)
        gate.complete(snapshot(taipei))
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun anOlderLoadCannotOverwriteANewerOne() {
        val slow = CompletableDeferred<WeatherSnapshot>()
        source.forPlace = { place -> if (place == taipei) slow.await() else snapshot(place) }
        val viewModel = viewModel()
        viewModel.selectPlace(taipei)
        viewModel.selectPlace(kaohsiung)
        slow.complete(snapshot(taipei))

        assertEquals(kaohsiung, viewModel.uiState.value.place)
        assertEquals(kaohsiung, viewModel.uiState.value.snapshot?.place)
        assertEquals(kaohsiung, places.selected)
    }

    @Test
    fun deviceLocationLoadsByCoordinatesAndForgetsTheChosenPlace() {
        places.selected = taipei
        source.atCoordinates = { _, _ -> snapshot(kaohsiung) }
        location = { Coordinates(22.63, 120.30) }
        val viewModel = viewModel()
        viewModel.loadDeviceLocation()

        val state = viewModel.uiState.value
        assertTrue(state.usesDeviceLocation)
        assertEquals(kaohsiung, state.place)
        assertEquals(listOf(Coordinates(22.63, 120.30)), source.coordinateLoads)
        assertNull(places.selected)
    }

    @Test
    fun failedDeviceLocationKeepsTheChosenPlace() {
        places.selected = taipei
        location = { throw LocationUnavailableException() }
        val viewModel = viewModel()
        viewModel.loadDeviceLocation()

        assertTrue(viewModel.uiState.value.message!!.contains("定位"))
        assertEquals(taipei, places.selected)
    }

    @Test
    fun outsideTaiwanAsksForAManualPlace() {
        source.atCoordinates = { _, _ -> throw OutsideTaiwanException() }
        val viewModel = viewModel()
        viewModel.loadDeviceLocation()
        assertTrue(viewModel.uiState.value.message!!.contains("臺灣"))
    }

    @Test
    fun refreshFollowsHowTheCurrentPlaceWasChosen() {
        source.forPlace = { snapshot(it) }
        source.atCoordinates = { _, _ -> snapshot(kaohsiung) }
        val viewModel = viewModel()

        viewModel.refresh()
        assertTrue(source.placeLoads.isEmpty() && source.coordinateLoads.isEmpty())

        viewModel.selectPlace(taipei)
        viewModel.refresh()
        assertEquals(listOf(taipei, taipei), source.placeLoads)

        viewModel.loadDeviceLocation()
        viewModel.refresh()
        assertEquals(2, source.coordinateLoads.size)
        assertEquals(2, source.placeLoads.size)
    }

    @Test
    fun rejectedKeyDuringLoadAsksForANewKey() {
        source.forPlace = { throw httpError(401) }
        val viewModel = viewModel()
        viewModel.selectPlace(taipei)

        assertTrue(viewModel.uiState.value.askForKey)
        assertTrue(viewModel.uiState.value.message!!.contains("授權碼"))
    }

    @Test
    fun serverErrorDoesNotAskForAKey() {
        source.forPlace = { throw httpError(500) }
        val viewModel = viewModel()
        viewModel.selectPlace(taipei)

        assertFalse(viewModel.uiState.value.askForKey)
        assertTrue(viewModel.uiState.value.message!!.contains("500"))
    }

    @Test
    fun favoritesAreToggledAndPersisted() {
        val viewModel = viewModel()
        viewModel.toggleFavorite(taipei)
        viewModel.toggleFavorite(kaohsiung)
        assertEquals(listOf(taipei, kaohsiung), viewModel.uiState.value.favorites)
        assertEquals(listOf(taipei, kaohsiung), PlaceStore(placePrefs).favorites)

        viewModel.toggleFavorite(taipei)
        assertEquals(listOf(kaohsiung), viewModel.uiState.value.favorites)
        assertEquals(listOf(kaohsiung), PlaceStore(placePrefs).favorites)
    }

    @Test
    fun savedFavoritesAreLoadedAtStart() {
        places.favorites = listOf(kaohsiung)
        assertEquals(listOf(kaohsiung), viewModel().uiState.value.favorites)
    }

    @Test
    fun acceptedKeyIsSavedAndClosesThePrompt() {
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.hasApiKey)
        viewModel.setAskForKey(true)

        viewModel.saveApiKey("KEY")
        val state = viewModel.uiState.value
        assertEquals("KEY", credentials.apiKey)
        assertFalse(state.askForKey)
        assertFalse(state.verifyingKey)
        assertNull(state.keyError)
        assertTrue(state.hasApiKey)
    }

    @Test
    fun savedKeyAsksForAnotherStart() {
        val viewModel = viewModel()
        assertTrue(viewModel.claimStart())

        viewModel.saveApiKey("KEY")
        assertTrue(viewModel.uiState.value.startPending)
        assertTrue(viewModel.claimStart())
        assertFalse(viewModel.claimStart())
    }

    @Test
    fun rejectedKeyIsNotSaved() {
        source.verify = { throw httpError(403) }
        val viewModel = viewModel()
        assertTrue(viewModel.claimStart())
        viewModel.setAskForKey(true)

        viewModel.saveApiKey("BAD")
        val state = viewModel.uiState.value
        assertTrue(state.keyError!!.contains("授權碼"))
        assertEquals("", credentials.apiKey)
        assertTrue(state.askForKey)
        assertFalse(state.verifyingKey)
        assertFalse(state.hasApiKey)
        assertFalse(state.startPending)
    }

    @Test
    fun serverErrorWhileCheckingTheKeyIsReportedAndNotSaved() {
        source.verify = { throw httpError(503) }
        val viewModel = viewModel()
        viewModel.saveApiKey("KEY")
        assertTrue(viewModel.uiState.value.keyError!!.contains("503"))
        assertEquals("", credentials.apiKey)
    }

    @Test
    fun noConnectionWhileCheckingTheKeyIsReportedAndNotSaved() {
        source.verify = { throw IOException("offline") }
        val viewModel = viewModel()
        viewModel.saveApiKey("KEY")
        assertTrue(viewModel.uiState.value.keyError!!.contains("無法連線"))
        assertEquals("", credentials.apiKey)
    }

    @Test
    fun unreadableReplyWhileCheckingTheKeyIsReportedInsteadOfCrashing() {
        source.verify = { throw IllegalStateException("not JSON") }
        val viewModel = viewModel()
        viewModel.saveApiKey("KEY")

        val state = viewModel.uiState.value
        assertTrue(state.keyError!!.contains("無法讀取"))
        assertFalse(state.verifyingKey)
        assertEquals("", credentials.apiKey)
    }

    @Test
    fun keyCheckIsShownWhileItRunsAndIsNotStartedTwice() {
        val gate = CompletableDeferred<Unit>()
        val candidates = mutableListOf<String>()
        source.verify = { candidates += it; gate.await() }
        val viewModel = viewModel()

        viewModel.saveApiKey("FIRST")
        assertTrue(viewModel.uiState.value.verifyingKey)
        viewModel.saveApiKey("SECOND")
        gate.complete(Unit)

        assertEquals(listOf("FIRST"), candidates)
        assertEquals("FIRST", credentials.apiKey)
        assertFalse(viewModel.uiState.value.verifyingKey)
    }

    @Test
    fun keyErrorIsClearedByEditingOrClosingThePrompt() {
        source.verify = { throw httpError(403) }
        val viewModel = viewModel()

        viewModel.saveApiKey("BAD")
        viewModel.clearKeyError()
        assertNull(viewModel.uiState.value.keyError)

        viewModel.saveApiKey("BAD")
        viewModel.setAskForKey(false)
        assertNull(viewModel.uiState.value.keyError)
    }

    @Test
    fun placePickerVisibilityIsPartOfTheState() {
        val viewModel = viewModel()
        assertFalse(viewModel.uiState.value.showPicker)
        viewModel.setShowPicker(true)
        assertTrue(viewModel.uiState.value.showPicker)
        viewModel.setShowPicker(false)
        assertFalse(viewModel.uiState.value.showPicker)
    }

    @Test
    fun failedDeviceLocationGoesBackToThePlaceOnScreen() {
        source.forPlace = { snapshot(it) }
        val viewModel = viewModel()
        viewModel.selectPlace(taipei)
        location = { throw LocationUnavailableException() }
        viewModel.loadDeviceLocation()

        val state = viewModel.uiState.value
        assertFalse(state.usesDeviceLocation)
        assertEquals(taipei, state.place)
        assertEquals(taipei, state.snapshot?.place)
        assertTrue(state.message!!.contains("定位"))

        viewModel.refresh()
        assertEquals(listOf(taipei, taipei), source.placeLoads)
        assertTrue(source.coordinateLoads.isEmpty())
    }

    @Test
    fun failedPlaceGoesBackToThePlaceOnScreen() {
        source.forPlace = { place -> if (place == taipei) snapshot(place) else throw IOException("down") }
        val viewModel = viewModel()
        viewModel.selectPlace(taipei)
        viewModel.selectPlace(kaohsiung)

        val state = viewModel.uiState.value
        assertEquals(taipei, state.place)
        assertEquals(taipei, state.snapshot?.place)
        assertFalse(state.loading)
        assertNotNull(state.message)
    }

    @Test
    fun failedPlaceAfterDeviceLocationGoesBackToDeviceLocation() {
        source.atCoordinates = { _, _ -> snapshot(taipei) }
        source.forPlace = { throw IOException("down") }
        val viewModel = viewModel()
        viewModel.loadDeviceLocation()
        viewModel.selectPlace(kaohsiung)

        val state = viewModel.uiState.value
        assertTrue(state.usesDeviceLocation)
        assertEquals(taipei, state.place)
        assertEquals(taipei, state.snapshot?.place)
    }

    @Test
    fun failedFirstLoadKeepsThePlaceSoItCanBeRetried() {
        source.forPlace = { throw IOException("down") }
        val viewModel = viewModel()
        viewModel.selectPlace(taipei)
        assertEquals(taipei, viewModel.uiState.value.place)

        source.forPlace = { snapshot(it) }
        viewModel.refresh()
        assertEquals(taipei, viewModel.uiState.value.snapshot?.place)
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun townListsComeBackAsResults() = runBlocking {
        source.townsOf = { listOf("中正區", "大安區") }
        assertEquals(listOf("中正區", "大安區"), viewModel().towns("臺北市").getOrThrow())

        source.townsOf = { throw IOException("offline") }
        assertTrue(viewModel().towns("臺北市").isFailure)
    }

    @Test
    fun messageCanBeShownAndStopsLoading() {
        val viewModel = viewModel()
        viewModel.showMessage("需要授權碼")
        assertEquals("需要授權碼", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.loading)
    }
}
