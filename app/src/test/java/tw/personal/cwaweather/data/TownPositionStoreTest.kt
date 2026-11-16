package tw.personal.cwaweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TownPositionStoreTest {
    private val prefs = FakeSharedPreferences()
    private val zhongzheng = Place("臺北市", "中正區")

    @Test
    fun unknownTownHasNoPosition() {
        assertNull(TownPositionStore(prefs)[zhongzheng])
    }

    @Test
    fun keptPositionSurvivesANewStore() {
        TownPositionStore(prefs)[zhongzheng] = Coordinates(25.046058, 121.516565)
        assertEquals(Coordinates(25.046058, 121.516565), TownPositionStore(prefs)[zhongzheng])
    }

    @Test
    fun townsWithTheSameNameInOtherCountiesAreKeptApart() {
        val store = TownPositionStore(prefs)
        store[Place("臺南市", "東區")] = Coordinates(22.98, 120.22)
        store[Place("新竹市", "東區")] = Coordinates(24.80, 120.99)
        assertEquals(Coordinates(22.98, 120.22), store[Place("臺南市", "東區")])
        assertEquals(Coordinates(24.80, 120.99), store[Place("新竹市", "東區")])
    }

    @Test
    fun unreadableSavedValueIsTreatedAsUnknown() {
        prefs.edit().putString("臺北市|中正區", "not a position").apply()
        assertNull(TownPositionStore(prefs)[zhongzheng])
    }
}
