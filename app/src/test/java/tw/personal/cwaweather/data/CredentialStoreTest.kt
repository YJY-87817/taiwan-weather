package tw.personal.cwaweather.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialStoreTest {
    private val prefs = FakeSharedPreferences()

    @Test
    fun missingKeyReadsAsEmpty() {
        assertEquals("", CredentialStore(prefs).apiKey)
    }

    @Test
    fun savedKeySurvivesANewStore() {
        CredentialStore(prefs).apiKey = "CWA-123"
        assertEquals("CWA-123", CredentialStore(prefs).apiKey)
    }

    @Test
    fun newKeyReplacesTheOldOne() {
        val store = CredentialStore(prefs)
        store.apiKey = "old"
        store.apiKey = "new"
        assertEquals("new", store.apiKey)
    }
}
