package com.kirivsoft.directlink.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

interface StunProbe {
    fun query(serverHost: String, serverPort: Int, localPort: Int, timeoutMs: Int = 1_500): StunResult
}

class StunClient(
    private val random: SecureRandom = SecureRandom()
) : StunProbe {
    override fun query(serverHost: String, serverPort: Int, localPort: Int, timeoutMs: Int): StunResult {
        DatagramSocket(localPort).use { socket ->
            socket.soTimeout = timeoutMs
            val txId = ByteArray(TRANSACTION_ID_SIZE).also(random::nextBytes)
            val request = buildBindingRequest(txId)
            val address = InetAddress.getByName(serverHost)
            socket.send(DatagramPacket(request, request.size, address, serverPort))

            val buffer = ByteArray(1024)
            val response = DatagramPacket(buffer, buffer.size)
            return runCatching {
                socket.receive(response)
                parseBindingResponse(buffer.copyOf(response.length), txId, serverHost)
            }.getOrElse { StunResult.Error(serverHost, it.message ?: "STUN query failed") }
        }
    }

    fun buildBindingRequest(transactionId: ByteArray): ByteArray {
        require(transactionId.size == TRANSACTION_ID_SIZE) { "STUN transaction id must be 12 bytes" }
        return ByteBuffer.allocate(STUN_HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(BINDING_REQUEST.toShort())
            .putShort(0)
            .putInt(MAGIC_COOKIE)
            .put(transactionId)
            .array()
    }

    fun parseBindingResponse(bytes: ByteArray, transactionId: ByteArray, server: String): StunResult {
        if (bytes.size < STUN_HEADER_SIZE) return StunResult.Error(server, "STUN response is too short")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val type = buffer.short.toInt() and 0xffff
        val length = buffer.short.toInt() and 0xffff
        val cookie = buffer.int
        val responseTxId = ByteArray(TRANSACTION_ID_SIZE).also(buffer::get)

        if (type != BINDING_RESPONSE) return StunResult.Error(server, "Unexpected STUN message type $type")
        if (cookie != MAGIC_COOKIE) return StunResult.Error(server, "Invalid STUN magic cookie")
        if (!responseTxId.contentEquals(transactionId)) return StunResult.Error(server, "Transaction id mismatch")
        if (bytes.size < STUN_HEADER_SIZE + length) return StunResult.Error(server, "Truncated STUN attributes")

        val end = STUN_HEADER_SIZE + length
        while (buffer.position() + 4 <= end) {
            val attrType = buffer.short.toInt() and 0xffff
            val attrLength = buffer.short.toInt() and 0xffff
            if (buffer.position() + attrLength > end) return StunResult.Error(server, "Truncated STUN attribute")
            val attrStart = buffer.position()

            when (attrType) {
                XOR_MAPPED_ADDRESS -> parseXorMappedAddress(bytes, attrStart, attrLength)?.let {
                    return StunResult.Success(server, it.ip, it.port)
                }
                MAPPED_ADDRESS -> parseMappedAddress(bytes, attrStart, attrLength)?.let {
                    return StunResult.Success(server, it.ip, it.port)
                }
            }

            val padded = (attrLength + 3) and -4
            buffer.position(attrStart + padded)
        }

        return StunResult.Error(server, "STUN response has no mapped address")
    }

    private fun parseMappedAddress(bytes: ByteArray, offset: Int, length: Int): MappedAddress? {
        if (length < 8) return null
        val buffer = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        val family = buffer.get().toInt() and 0xff
        if (family != IPV4_FAMILY) return null
        val port = buffer.short.toInt() and 0xffff
        val ipBytes = ByteArray(4).also(buffer::get)
        return MappedAddress(ipBytes.joinToString(".") { (it.toInt() and 0xff).toString() }, port)
    }

    private fun parseXorMappedAddress(bytes: ByteArray, offset: Int, length: Int): MappedAddress? {
        if (length < 8) return null
        val buffer = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        val family = buffer.get().toInt() and 0xff
        if (family != IPV4_FAMILY) return null
        val port = (buffer.short.toInt() and 0xffff) xor (MAGIC_COOKIE ushr 16)
        val cookieBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(MAGIC_COOKIE).array()
        val ipBytes = ByteArray(4).also(buffer::get)
        val decodedIp = ipBytes.mapIndexed { index, byte -> byte.toInt() xor cookieBytes[index].toInt() }
        return MappedAddress(decodedIp.joinToString(".") { (it and 0xff).toString() }, port)
    }

    private data class MappedAddress(val ip: String, val port: Int)

    companion object {
        private const val STUN_HEADER_SIZE = 20
        private const val TRANSACTION_ID_SIZE = 12
        private const val BINDING_REQUEST = 0x0001
        private const val BINDING_RESPONSE = 0x0101
        private const val MAGIC_COOKIE = 0x2112A442
        private const val MAPPED_ADDRESS = 0x0001
        private const val XOR_MAPPED_ADDRESS = 0x0020
        private const val IPV4_FAMILY = 0x01
    }
}

sealed class StunResult {
    data class Success(val server: String, val publicIp: String, val publicPort: Int) : StunResult()
    data class Error(val server: String, val reason: String) : StunResult()
}
