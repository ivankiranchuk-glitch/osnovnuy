package com.kirivsoft.directlink.packet

import com.kirivsoft.directlink.network.NatType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DlpSerializerTest {
    private val serializer = DlpSerializer()

    @Test
    fun `written packet parses with matching password`() {
        val file = createTempFile("directlink_", ".dlp")
        val packet = testPacket()

        serializer.write(packet, "pass", file)
        val result = serializer.parse(file, "pass", nowSeconds = 110)

        assertTrue(result is DlpParseResult.Success)
        result as DlpParseResult.Success
        assertEquals("Laptop", result.packet.deviceName)
        assertEquals("203.0.113.10", result.packet.publicIp)
        assertEquals(41000, result.packet.publicPort)
        assertEquals(NatType.RESTRICTED, result.packet.natType)
        file.delete()
    }

    @Test
    fun `written packet uses dlp2 container`() {
        val file = createTempFile("directlink_", ".dlp")
        val packet = testPacket()

        serializer.write(packet, "pass", file)
        val raw = file.readBytes()

        assertTrue(raw.copyOfRange(0, 4).contentEquals(byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), '2'.code.toByte())))
        file.delete()
    }

    @Test
    fun `written packet does not expose plaintext metadata`() {
        val file = createTempFile("directlink_", ".dlp")
        val packet = testPacket()

        serializer.write(packet, "pass", file)
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)

        assertFalse(raw.contains("Laptop"))
        assertFalse(raw.contains("uuid-1"))
        file.delete()
    }

    @Test
    fun `parser rejects wrong password`() {
        val file = createTempFile("directlink_", ".dlp")
        val packet = testPacket()

        serializer.write(packet, "pass", file)
        val result = serializer.parse(file, "wrong", nowSeconds = 110)

        assertTrue(result is DlpParseResult.InvalidPassword)
        file.delete()
    }

    @Test
    fun `parser rejects expired packet`() {
        val file = createTempFile("directlink_", ".dlp")
        val packet = testPacket(ttlSeconds = 5)

        serializer.write(packet, "pass", file)
        val result = serializer.parse(file, "pass", nowSeconds = 106)

        assertTrue(result is DlpParseResult.Expired)
        file.delete()
    }

    @Test
    fun `parser reads legacy dlp1 packet`() {
        val file = createTempFile("directlink_legacy_", ".dlp")
        val packet = testPacket()
        val plaintext = legacyPlaintext(packet)

        file.writeBytes(legacyEncrypt("pass", plaintext))
        val result = serializer.parse(file, "pass", nowSeconds = 110)

        assertTrue(result is DlpParseResult.Success)
        result as DlpParseResult.Success
        assertEquals("Laptop", result.packet.deviceName)
        assertEquals("uuid-1", result.packet.deviceUuid)
        file.delete()
    }

    private fun testPacket(ttlSeconds: Long = 60): DlpPacket = serializer.buildPacket(
        deviceName = "Laptop",
        deviceUuid = "uuid-1",
        platform = "JUnit",
        appVersion = "0.1.0",
        fingerprint = "abc123",
        publicIp = "203.0.113.10",
        publicPort = 41000,
        natType = NatType.RESTRICTED,
        password = "pass",
        ttlSeconds = ttlSeconds,
        nowSeconds = 100
    )

    private fun legacyPlaintext(packet: DlpPacket): ByteArray = buildString {
        appendLine("#DirectLink MVP packet")
        appendLine("version=${packet.version}")
        appendLine("packetId=${packet.packetId}")
        appendLine("deviceName=${packet.deviceName}")
        appendLine("deviceUuid=${packet.deviceUuid}")
        appendLine("platform=${packet.platform}")
        appendLine("appVersion=${packet.appVersion}")
        appendLine("fingerprint=${packet.fingerprint}")
        appendLine("publicIp=${packet.publicIp}")
        appendLine("publicPort=${packet.publicPort}")
        appendLine("natType=${packet.natType.name}")
        appendLine("issuedAt=${packet.issuedAt}")
        appendLine("expiresAt=${packet.expiresAt}")
    }.toByteArray()

    private fun legacyEncrypt(password: String, plaintext: ByteArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 120_000, 256))
            .encoded
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext)
        }
        return byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte()) + salt + nonce + ciphertext
    }
}
