package tw.personal.cwaweather.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreTest {
    private val prefs = FakeSharedPreferences()
    private val cipher = FakeCipher()

    private fun store() = CredentialStore(prefs, cipher)

    @Test
    fun missingKeyReadsAsEmpty() {
        assertEquals("", store().apiKey)
        assertFalse(store().takeLostKeyNotice())
    }

    @Test
    fun savedKeySurvivesANewStore() {
        store().save("CWA-123")
        assertEquals("CWA-123", store().apiKey)
    }

    @Test
    fun savedKeyIsNotStoredInPlainText() {
        store().save("CWA-123")
        val stored = prefs.all.values.map { it.toString() }
        assertTrue(stored.isNotEmpty())
        assertTrue(stored.none { it.contains("CWA-123") })
    }

    @Test
    fun newKeyReplacesTheOldOne() {
        val store = store()
        store.save("old")
        store.save("new")
        assertEquals("new", store.apiKey)
        assertEquals("new", store().apiKey)
    }

    @Test
    fun keyFromBeforeEncryptionIsMovedOverWhenRead() {
        prefs.edit().putString("cwa_api_key", "CWA-OLD").apply()

        assertEquals("CWA-OLD", store().apiKey)
        assertNull(prefs.getString("cwa_api_key", null))
        assertNotNull(prefs.getString("cwa_api_key_encrypted", null))
        assertEquals("CWA-OLD", store().apiKey)
    }

    @Test
    fun keyFromBeforeEncryptionKeepsWorkingWhenItCannotBeMoved() {
        prefs.edit().putString("cwa_api_key", "CWA-OLD").apply()
        cipher.encryptFails = true

        assertEquals("CWA-OLD", store().apiKey)
        assertEquals("CWA-OLD", prefs.getString("cwa_api_key", null))

        // Moved on a later start, once encryption works.
        cipher.encryptFails = false
        assertEquals("CWA-OLD", store().apiKey)
        assertNull(prefs.getString("cwa_api_key", null))
    }

    @Test
    fun keyThatCannotBeDecryptedIsDroppedAndReportedOnce() {
        store().save("CWA-123")
        cipher.decryptFails = true

        val store = store()
        assertEquals("", store.apiKey)
        assertNull(prefs.getString("cwa_api_key_encrypted", null))
        assertTrue(store.takeLostKeyNotice())
        assertFalse(store.takeLostKeyNotice())
    }

    @Test
    fun keyThatCannotBeEncryptedIsNotStoredAndTheOldOneStays() {
        val store = store()
        store.save("old")
        cipher.encryptFails = true

        assertThrows(KeyStorageException::class.java) { store.save("new") }
        assertEquals("old", store.apiKey)
        cipher.encryptFails = false
        assertEquals("old", store().apiKey)
    }

    @Test
    fun keyIsDecryptedOnlyOnce() {
        store().save("CWA-123")
        val store = store()
        assertEquals("CWA-123", store.apiKey)
        cipher.decryptFails = true
        assertEquals("CWA-123", store.apiKey)
    }
}
