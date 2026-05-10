package com.kirivsoft.directlink.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DlpCrypto(
    private val random: SecureRandom = SecureRandom(),
    private val legacyIterations: Int = 120_000,
    private val argonMemoryKb: Int = 64 * 1024,
    private val argonIterations: Int = 3,
    private val argonParallelism: Int = 1
) {
    fun encrypt(password: String, plaintext: ByteArray): ByteArray {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(CHACHA_NONCE_SIZE).also(random::nextBytes)
        val key = deriveArgon2idKey(password, salt)
        val ciphertext = chacha20Poly1305(true, key, nonce, plaintext)
        return MAGIC_V2 + salt + nonce + ciphertext
    }

    fun decrypt(password: String, container: ByteArray): DecryptResult = when {
        container.startsWith(MAGIC_V2) -> decryptV2(password, container)
        container.startsWith(MAGIC_V1) -> decryptLegacyV1(password, container)
        container.size < MAGIC_V2.size + SALT_SIZE + CHACHA_NONCE_SIZE + 1 -> DecryptResult.Malformed("Container is too short")
        else -> DecryptResult.Malformed("Unknown DLP container magic")
    }

    private fun decryptV2(password: String, container: ByteArray): DecryptResult {
        if (container.size < MAGIC_V2.size + SALT_SIZE + CHACHA_NONCE_SIZE + 1) {
            return DecryptResult.Malformed("Container is too short")
        }

        val saltStart = MAGIC_V2.size
        val nonceStart = saltStart + SALT_SIZE
        val ciphertextStart = nonceStart + CHACHA_NONCE_SIZE
        val salt = container.copyOfRange(saltStart, nonceStart)
        val nonce = container.copyOfRange(nonceStart, ciphertextStart)
        val ciphertext = container.copyOfRange(ciphertextStart, container.size)

        return try {
            val key = deriveArgon2idKey(password, salt)
            DecryptResult.Success(chacha20Poly1305(false, key, nonce, ciphertext))
        } catch (_: InvalidCipherTextException) {
            DecryptResult.InvalidPassword
        } catch (e: Exception) {
            DecryptResult.Malformed(e.message ?: "Cannot decrypt DLP container")
        }
    }

    private fun decryptLegacyV1(password: String, container: ByteArray): DecryptResult {
        if (container.size < MAGIC_V1.size + SALT_SIZE + LEGACY_NONCE_SIZE + 1) {
            return DecryptResult.Malformed("Container is too short")
        }

        val saltStart = MAGIC_V1.size
        val nonceStart = saltStart + SALT_SIZE
        val ciphertextStart = nonceStart + LEGACY_NONCE_SIZE
        val salt = container.copyOfRange(saltStart, nonceStart)
        val nonce = container.copyOfRange(nonceStart, ciphertextStart)
        val ciphertext = container.copyOfRange(ciphertextStart, container.size)

        return try {
            val key = deriveLegacyKey(password, salt)
            val plaintext = Cipher.getInstance(LEGACY_CIPHER).run {
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

    private fun deriveArgon2idKey(password: String, salt: ByteArray): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(argonMemoryKb)
            .withIterations(argonIterations)
            .withParallelism(argonParallelism)
            .build()
        val key = ByteArray(KEY_BYTES)
        Argon2BytesGenerator().run {
            init(parameters)
            generateBytes(password.toCharArray(), key)
        }
        return key
    }

    private fun deriveLegacyKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, legacyIterations, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance(LEGACY_KDF).generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun chacha20Poly1305(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
        val output = ByteArray(cipher.getOutputSize(input.size))
        val processed = cipher.processBytes(input, 0, input.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        return output.copyOf(processed + finalized)
    }

    companion object {
        private val MAGIC_V1 = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
        private val MAGIC_V2 = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte())
        private const val LEGACY_CIPHER = "AES/GCM/NoPadding"
        private const val LEGACY_KDF = "PBKDF2WithHmacSHA256"
        private const val SALT_SIZE = 16
        private const val LEGACY_NONCE_SIZE = 12
        private const val CHACHA_NONCE_SIZE = 12
        private const val KEY_BYTES = 32
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

sealed class DecryptResult {
    data class Success(val plaintext: ByteArray) : DecryptResult()
    object InvalidPassword : DecryptResult()
    data class Malformed(val reason: String) : DecryptResult()
}
