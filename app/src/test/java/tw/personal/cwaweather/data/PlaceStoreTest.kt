package tw.personal.cwaweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceStoreTest {
    private val prefs = FakeSharedPreferences()
    private val taipei = Place("臺北市", "中正區")
    private val kaohsiung = Place("高雄市", "左營區")

    @Test
    fun startsEmpty() {
        val store = PlaceStore(prefs)
        assertNull(store.selected)
        assertEquals(emptyList<Place>(), store.favorites)
    }

    @Test
    fun selectedPlaceSurvivesANewStore() {
        PlaceStore(prefs).selected = taipei
        assertEquals(taipei, PlaceStore(prefs).selected)
    }

    @Test
    fun clearingSelectedPlaceForgetsIt() {
        val store = PlaceStore(prefs)
        store.selected = taipei
        store.selected = null
        assertNull(PlaceStore(prefs).selected)
    }

    @Test
    fun favoritesKeepTheOrderTheyWereAdded() {
        val store = PlaceStore(prefs)
        store.favorites = listOf(kaohsiung, taipei)
        assertEquals(listOf(kaohsiung, taipei), PlaceStore(prefs).favorites)
    }

    @Test
    fun emptyFavoritesListRoundTrips() {
        val store = PlaceStore(prefs)
        store.favorites = listOf(taipei)
        store.favorites = emptyList()
        assertEquals(emptyList<Place>(), PlaceStore(prefs).favorites)
    }

    @Test
    fun malformedFavoriteLinesAreSkipped() {
        prefs.edit().putString("favorites", "臺北市|中正區\nbroken\n高雄市|左營區|extra").apply()
        assertEquals(listOf(taipei), PlaceStore(prefs).favorites)
    }
}
