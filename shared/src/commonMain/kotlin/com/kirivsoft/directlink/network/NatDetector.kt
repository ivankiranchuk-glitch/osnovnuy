package com.kirivsoft.directlink.network

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

enum class NatType {
    OPEN,
    FULL_CONE,
    RESTRICTED,
    PORT_RESTRICTED,
    SYMMETRIC,
    UNKNOWN
}

data class NatDetectionResult(
    val publicIp: String,
    val publicPort: Int,
    val localIp: String,
    val localPort: Int,
    val tcpPort: Int,
    val natType: NatType,
    val stunServer: String,
    val portStable: Boolean
)

class NatDetector {
    fun detect(preferredUdpPort: Int = 0, preferredTcpPort: Int = 0): NatDetectionResult {
        val udpPort = findAvailableUdpPort(preferredUdpPort)
        return NatDetectionResult(
            publicIp = "0.0.0.0",
            publicPort = udpPort,
            localIp = getLocalIpAddress(),
            localPort = udpPort,
            tcpPort = findAvailableTcpPort(preferredTcpPort),
            natType = NatType.UNKNOWN,
            stunServer = "not-configured",
            portStable = false
        )
    }

    fun findAvailableUdpPort(preferred: Int = 0): Int {
        if (preferred <= 0) return DatagramSocket(0).use { it.localPort }
        return runCatching { DatagramSocket(preferred).use { preferred } }
            .getOrElse { DatagramSocket(0).use { socket -> socket.localPort } }
    }

    fun findAvailableTcpPort(preferred: Int = 0): Int {
        if (preferred <= 0) return ServerSocket(0).use { it.localPort }
        return runCatching { ServerSocket(preferred).use { preferred } }
            .getOrElse { ServerSocket(0).use { socket -> socket.localPort } }
    }

    fun getLocalIpAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"
}
