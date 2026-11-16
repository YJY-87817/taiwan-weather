package tw.personal.cwaweather.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM with a key that lives in the Android Keystore and cannot be read out of it. The sealed
 * bytes are the 12-byte nonce followed by the ciphertext and its tag.
 */
class KeystoreCipher(private val alias: String = "cwa_api_key") : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val existing = existingKey() ?: return encryptWith(newKey(), plain)
        return try {
            encryptWith(existing, plain)
        } catch (e: Exception) {
            // A key that can no longer encrypt would block every save. Nothing sealed with it can be
            // read either, so it is replaced; if the new key fails too, the error goes to the caller.
            keyStore().deleteEntry(alias)
            encryptWith(newKey(), plain)
        }
    }

    private fun encryptWith(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        // A missing key is not replaced here: nothing sealed with the old one could be read anyway.
        val key = existingKey() ?: throw IllegalStateException("Keystore key $alias is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun newKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
