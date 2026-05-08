package com.kirivsoft.directlink.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class HolePunchingManager {
    fun selectStrategy(local: NatType, remote: NatType): PunchStrategy = when {
        local == NatType.SYMMETRIC && remote == NatType.SYMMETRIC -> PunchStrategy.RELAY_REQUIRED
        local == NatType.OPEN || remote == NatType.OPEN -> PunchStrategy.DIRECT
        local == NatType.SYMMETRIC || remote == NatType.SYMMETRIC -> PunchStrategy.PORT_GUESSING
        local == NatType.UNKNOWN || remote == NatType.UNKNOWN -> PunchStrategy.SIMULTANEOUS
        else -> PunchStrategy.SIMULTANEOUS
    }

    fun punch(
        localSocket: DatagramSocket,
        localNatType: NatType,
        remote: PeerEndpoint,
        timeoutMs: Int = 5_000
    ): PunchResult {
        return when (selectStrategy(localNatType, remote.natType)) {
            PunchStrategy.DIRECT -> directPunch(localSocket, remote, timeoutMs)
            PunchStrategy.SIMULTANEOUS -> simultaneousPunch(localSocket, remote, timeoutMs)
            PunchStrategy.PORT_GUESSING -> portGuessingPunch(localSocket, remote, timeoutMs)
            PunchStrategy.RELAY_REQUIRED -> PunchResult.NeedsRelay("Symmetric NAT on both sides requires relay")
        }
    }

    private fun directPunch(socket: DatagramSocket, remote: PeerEndpoint, timeoutMs: Int): PunchResult =
        sendAndAwait(socket, listOf(remote.publicPort), remote.publicIp, timeoutMs, PunchStrategy.DIRECT)

    private fun simultaneousPunch(socket: DatagramSocket, remote: PeerEndpoint, timeoutMs: Int): PunchResult =
        sendAndAwait(socket, listOf(remote.publicPort), remote.publicIp, timeoutMs, PunchStrategy.SIMULTANEOUS)

    private fun portGuessingPunch(socket: DatagramSocket, remote: PeerEndpoint, timeoutMs: Int): PunchResult {
        val ports = (-3..3).map { remote.publicPort + it }.filter { it in 1..65535 }
        return sendAndAwait(socket, ports, remote.publicIp, timeoutMs, PunchStrategy.PORT_GUESSING)
    }

    private fun sendAndAwait(
        socket: DatagramSocket,
        candidatePorts: List<Int>,
        remoteIp: String,
        timeoutMs: Int,
        strategy: PunchStrategy
    ): PunchResult {
        val address = InetAddress.getByName(remoteIp)
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + timeoutMs
        socket.soTimeout = 250

        while (System.currentTimeMillis() < deadline) {
            for (port in candidatePorts) {
                val payload = PUNCH_MAGIC + strategy.name.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size, address, port))
            }

            val buffer = ByteArray(256)
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching {
                socket.receive(packet)
                val bytes = buffer.copyOf(packet.length)
                if (bytes.startsWith(PUNCH_MAGIC)) {
                    return PunchResult.Success(
                        remoteAddress = InetSocketAddress(packet.address, packet.port),
                        strategy = strategy,
                        rttMs = System.currentTimeMillis() - startedAt
                    )
                }
            }
        }

        return PunchResult.Failed("No UDP punch response before timeout")
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    companion object {
        private val PUNCH_MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'P'.code.toByte(), 'P'.code.toByte())
    }
}

data class PeerEndpoint(
    val publicIp: String,
    val publicPort: Int,
    val natType: NatType
)

enum class PunchStrategy {
    DIRECT,
    SIMULTANEOUS,
    PORT_GUESSING,
    RELAY_REQUIRED
}

sealed class PunchResult {
    data class Success(
        val remoteAddress: InetSocketAddress,
        val strategy: PunchStrategy,
        val rttMs: Long
    ) : PunchResult()

    data class NeedsRelay(val reason: String) : PunchResult()
    data class Failed(val reason: String) : PunchResult()
}
