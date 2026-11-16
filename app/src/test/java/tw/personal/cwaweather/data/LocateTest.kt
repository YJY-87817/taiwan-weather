package tw.personal.cwaweather.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocateTest {
    private val now = 1_800_000_000_000L
    private val timeout = 15_000L
    private val maxAge = 30L * 60 * 1000
    private val here = LocationFix(25.04, 121.52, now)
    private val earlier = LocationFix(22.63, 120.30, now - 10 * 60 * 1000)

    private suspend fun locateWith(fresh: suspend () -> LocationFix?, lastKnown: suspend () -> LocationFix?) =
        locate(fresh, lastKnown, nowMillis = { now }, timeoutMillis = timeout, maxAgeMillis = maxAge)

    @Test
    fun freshFixIsUsedWithoutAskingForTheLastKnownOne() = runTest {
        var askedLastKnown = false
        val result = locateWith(fresh = { here }, lastKnown = { askedLastKnown = true; earlier })
        assertEquals(Coordinates(25.04, 121.52), result)
        assertFalse(askedLastKnown)
    }

    @Test
    fun noFreshFixFallsBackToARecentLastKnownOne() = runTest {
        assertEquals(Coordinates(22.63, 120.30), locateWith(fresh = { null }, lastKnown = { earlier }))
    }

    @Test
    fun slowFreshFixIsGivenUpAfterTheTimeout() = runTest {
        var cancelled = false
        val result = locateWith(
            fresh = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            lastKnown = { earlier },
        )
        assertEquals(Coordinates(22.63, 120.30), result)
        assertEquals(timeout, currentTime)
        assertTrue(cancelled)
    }

    @Test
    fun freshFixJustInsideTheTimeoutIsUsed() = runTest {
        val result = locateWith(fresh = { delay(timeout - 1); here }, lastKnown = { earlier })
        assertEquals(Coordinates(25.04, 121.52), result)
    }

    @Test
    fun lastKnownFixAtTheAgeLimitIsStillUsed() = runTest {
        val atLimit = LocationFix(22.63, 120.30, now - maxAge)
        assertEquals(Coordinates(22.63, 120.30), locateWith(fresh = { null }, lastKnown = { atLimit }))
    }

    @Test
    fun lastKnownFixPastTheAgeLimitIsNotUsed() {
        val tooOld = LocationFix(22.63, 120.30, now - maxAge - 1)
        assertThrows(LocationUnavailableException::class.java) {
            runTest { locateWith(fresh = { null }, lastKnown = { tooOld }) }
        }
    }

    @Test
    fun noFixAtAllIsUnavailable() {
        assertThrows(LocationUnavailableException::class.java) {
            runTest { locateWith(fresh = { null }, lastKnown = { null }) }
        }
    }
}
