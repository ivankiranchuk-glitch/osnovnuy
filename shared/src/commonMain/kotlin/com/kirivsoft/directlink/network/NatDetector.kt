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

class NatDetector(
    private val stunProbe: StunProbe = StunClient(),
    private val stunServers: List<Pair<String, Int>> = DEFAULT_STUN_SERVERS
) {
    fun detect(preferredUdpPort: Int = 0, preferredTcpPort: Int = 0): NatDetectionResult {
        val udpPort = findAvailableUdpPort(preferredUdpPort)
        val tcpPort = findAvailableTcpPort(preferredTcpPort)
        val localIp = getLocalIpAddress()

        val stunResults = stunServers
            .asSequence()
            .map { (host, port) -> stunProbe.query(host, port, udpPort) }
            .toList()

        val successes = stunResults.filterIsInstance<StunResult.Success>()
        val first = successes.firstOrNull()
        if (first == null) {
            return NatDetectionResult(
                publicIp = "0.0.0.0",
                publicPort = udpPort,
                localIp = localIp,
                localPort = udpPort,
                tcpPort = tcpPort,
                natType = NatType.UNKNOWN,
                stunServer = stunResults.firstOrNull()?.let { (it as? StunResult.Error)?.server } ?: "not-configured",
                portStable = false
            )
        }

        val portStable = successes.map { it.publicPort }.distinct().size == 1
        val sameAddress = first.publicIp == localIp && first.publicPort == udpPort
        return NatDetectionResult(
            publicIp = first.publicIp,
            publicPort = first.publicPort,
            localIp = localIp,
            localPort = udpPort,
            tcpPort = tcpPort,
            natType = if (sameAddress) NatType.OPEN else NatType.UNKNOWN,
            stunServer = first.server,
            portStable = portStable
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

    companion object {
        val DEFAULT_STUN_SERVERS = listOf(
            "stun.l.google.com" to 19302,
            "stun1.l.google.com" to 19302,
            "stun.cloudflare.com" to 3478
        )
    }
}
