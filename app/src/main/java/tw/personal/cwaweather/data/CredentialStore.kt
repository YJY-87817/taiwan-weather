package tw.personal.cwaweather.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Base64

/** Turns a secret into bytes that only this app on this phone can turn back. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

/** Thrown when the phone cannot encrypt the key, in which case the key is not stored at all. */
class KeyStorageException(cause: Throwable) : Exception(cause)

/**
 * Holds the user's CWA authorization code in app-private storage, excluded from backups and
 * encrypted with a key that [cipher] keeps in the Android Keystore.
 *
 * A code that can no longer be decrypted, for example after the phone's lock screen was reset, is
 * dropped and reported once through [takeLostKeyNotice], so the user can be asked to enter it again.
 */
class CredentialStore(private val prefs: SharedPreferences, private val cipher: SecretCipher) {
    constructor(context: Context) :
        this(context.getSharedPreferences("credentials", Context.MODE_PRIVATE), KeystoreCipher())

    // Decrypting on every request would be wasted work, so the code is kept in memory once read.
    @Volatile private var cached: String? = null
    @Volatile private var lostKey = false

    val apiKey: String
        get() = cached ?: load().also { cached = it }

    /** Stores [key] encrypted, or throws [KeyStorageException] and leaves the stored code as it was. */
    fun save(key: String) {
        val sealed = seal(key)
        prefs.edit {
            putString(KEY_ENCRYPTED, sealed)
            remove(KEY_PLAIN)
        }
        cached = key
        lostKey = false
    }

    /** True once after a stored code was found that could not be decrypted. */
    fun takeLostKeyNotice(): Boolean = lostKey.also { lostKey = false }

    private fun load(): String {
        prefs.getString(KEY_ENCRYPTED, null)?.let { sealed ->
            return try {
                String(cipher.decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)
            } catch (e: Exception) {
                prefs.edit { remove(KEY_ENCRYPTED) }
                lostKey = true
                ""
            }
        }
        // A code saved before encryption was added is moved over the first time it is read.
        val plain = prefs.getString(KEY_PLAIN, null) ?: return ""
        try {
            save(plain)
        } catch (e: KeyStorageException) {
            // It was already stored this way, so it keeps working, and moving it is tried again next time.
        }
        return plain
    }

    private fun seal(key: String): String = try {
        Base64.getEncoder().encodeToString(cipher.encrypt(key.toByteArray(Charsets.UTF_8)))
    } catch (e: Exception) {
        throw KeyStorageException(e)
    }

    private companion object {
        const val KEY_PLAIN = "cwa_api_key"
        const val KEY_ENCRYPTED = "cwa_api_key_encrypted"
    }
}
