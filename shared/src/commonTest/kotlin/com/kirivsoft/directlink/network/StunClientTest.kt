package com.kirivsoft.directlink.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StunClientTest {
    private val client = StunClient()

    @Test
    fun `binding request has expected header`() {
        val txId = ByteArray(12) { it.toByte() }

        val request = client.buildBindingRequest(txId)
        val buffer = ByteBuffer.wrap(request).order(ByteOrder.BIG_ENDIAN)

        assertEquals(0x0001, buffer.short.toInt() and 0xffff)
        assertEquals(0, buffer.short.toInt() and 0xffff)
        assertEquals(0x2112A442, buffer.int)
    }

    @Test
    fun `parser reads xor mapped address`() {
        val txId = ByteArray(12) { (it + 1).toByte() }
        val response = xorMappedResponse(txId, "203.0.113.7", 54321)

        val result = client.parseBindingResponse(response, txId, "stun-test")

        assertTrue(result is StunResult.Success)
        result as StunResult.Success
        assertEquals("203.0.113.7", result.publicIp)
        assertEquals(54321, result.publicPort)
    }

    @Test
    fun `parser rejects mismatched transaction id`() {
        val txId = ByteArray(12) { (it + 1).toByte() }
        val response = xorMappedResponse(txId, "203.0.113.7", 54321)
        val wrongTxId = ByteArray(12) { (it + 2).toByte() }

        val result = client.parseBindingResponse(response, wrongTxId, "stun-test")

        assertTrue(result is StunResult.Error)
    }

    private fun xorMappedResponse(txId: ByteArray, ip: String, port: Int): ByteArray {
        val cookie = 0x2112A442
        val cookieBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(cookie).array()
        val encodedPort = port xor (cookie ushr 16)
        val encodedIp = ip.split(".")
            .map { it.toInt().toByte() }
            .mapIndexed { index, byte -> (byte.toInt() xor cookieBytes[index].toInt()).toByte() }
            .toByteArray()

        val attr = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .putShort(0x0020)
            .putShort(8)
            .put(0)
            .put(0x01)
            .putShort(encodedPort.toShort())
            .put(encodedIp)
            .array()

        return ByteBuffer.allocate(20 + attr.size).order(ByteOrder.BIG_ENDIAN)
            .putShort(0x0101)
            .putShort(attr.size.toShort())
            .putInt(cookie)
            .put(txId)
            .put(attr)
            .array()
    }
}
