package com.kirivsoft.directlink.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DlpCrypto(
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = 120_000
) {
    fun encrypt(password: String, plaintext: ByteArray): ByteArray {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val key = deriveKey(password, salt)
        val ciphertext = Cipher.getInstance(CIPHER).run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            doFinal(plaintext)
        }
        return MAGIC + salt + nonce + ciphertext
    }

    fun decrypt(password: String, container: ByteArray): DecryptResult {
        if (container.size < MAGIC.size + SALT_SIZE + NONCE_SIZE + 1) {
            return DecryptResult.Malformed("Container is too short")
        }
        if (!container.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return DecryptResult.Malformed("Unknown DLP container magic")
        }

        val saltStart = MAGIC.size
        val nonceStart = saltStart + SALT_SIZE
        val ciphertextStart = nonceStart + NONCE_SIZE
        val salt = container.copyOfRange(saltStart, nonceStart)
        val nonce = container.copyOfRange(nonceStart, ciphertextStart)
        val ciphertext = container.copyOfRange(ciphertextStart, container.size)

        return try {
            val key = deriveKey(password, salt)
            val plaintext = Cipher.getInstance(CIPHER).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                doFinal(ciphertext)
            }
            DecryptResult.Success(plaintext)
        } catch (_: AEADBadTagException) {
            DecryptResult.InvalidPassword
        } catch (e: Exception) {
            DecryptResult.Malformed(e.message ?: "Cannot decrypt DLP container")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    companion object {
        private val MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val KDF = "PBKDF2WithHmacSHA256"
        private const val SALT_SIZE = 16
        private const val NONCE_SIZE = 12
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}

sealed class DecryptResult {
    data class Success(val plaintext: ByteArray) : DecryptResult()
    object InvalidPassword : DecryptResult()
    data class Malformed(val reason: String) : DecryptResult()
}
