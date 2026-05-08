package com.kirivsoft.directlink.packet

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DlpSerializerTest {
    private val serializer = DlpSerializer()

    @Test
    fun `written packet parses with matching password`() {
        val file = createTempFile("directlink_", ".dlp")
        val packet = testPacket()

        serializer.write(packet, "pass", file)
        val result = serializer.parse(file, "pass", nowSeconds = 110)

        assertTrue(result is DlpParseResult.Success)
        assertEquals("Laptop", (result as DlpParseResult.Success).packet.deviceName)
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

    private fun testPacket(ttlSeconds: Long = 60): DlpPacket = serializer.buildPacket(
        deviceName = "Laptop",
        deviceUuid = "uuid-1",
        platform = "JUnit",
        appVersion = "0.1.0",
        fingerprint = "abc123",
        password = "pass",
        ttlSeconds = ttlSeconds,
        nowSeconds = 100
    )
}
