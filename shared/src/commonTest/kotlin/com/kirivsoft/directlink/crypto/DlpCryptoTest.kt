package com.kirivsoft.directlink.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DlpCryptoTest {
    private val crypto = DlpCrypto()

    @Test
    fun `encrypt decrypt round trip`() {
        val plaintext = "DirectLink secret packet".toByteArray()

        val encrypted = crypto.encrypt("pass", plaintext)
        val decrypted = crypto.decrypt("pass", encrypted)

        assertTrue(decrypted is DecryptResult.Success)
        assertArrayEquals(plaintext, (decrypted as DecryptResult.Success).plaintext)
    }

    @Test
    fun `decrypt rejects wrong password`() {
        val encrypted = crypto.encrypt("pass", "secret".toByteArray())

        val decrypted = crypto.decrypt("wrong", encrypted)

        assertTrue(decrypted is DecryptResult.InvalidPassword)
    }

    @Test
    fun `decrypt rejects malformed input`() {
        val decrypted = crypto.decrypt("pass", byteArrayOf(1, 2, 3))

        assertTrue(decrypted is DecryptResult.Malformed)
    }
}
