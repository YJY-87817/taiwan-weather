package tw.personal.cwaweather.data

/** A reversible stand-in for the Keystore, which unit tests cannot reach, with switchable failures. */
class FakeCipher : SecretCipher {
    var encryptFails = false
    var decryptFails = false

    override fun encrypt(plain: ByteArray): ByteArray {
        if (encryptFails) throw IllegalStateException("keystore unavailable")
        return MARKER + plain.map { (it.toInt() xor 0x5A).toByte() }
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        if (decryptFails) throw IllegalStateException("key invalidated")
        check(sealed.take(MARKER.size) == MARKER.toList()) { "not sealed by this cipher" }
        return sealed.drop(MARKER.size).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private companion object {
        val MARKER = byteArrayOf(1, 2, 3)
    }
}
