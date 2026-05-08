package com.kirivsoft.directlink.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NatDetectorTest {
    private val detector = NatDetector(stunProbe = FakeStunProbe(emptyList()), stunServers = emptyList())

    @Test
    fun `detect falls back to unknown when STUN is unavailable`() {
        val result = detector.detect()

        assertEquals(NatType.UNKNOWN, result.natType)
        assertTrue(result.localPort > 0)
        assertTrue(result.tcpPort > 0)
        assertTrue(result.localIp.isNotBlank())
    }

    @Test
    fun `detect uses first successful STUN result`() {
        val stun = FakeStunProbe(
            listOf(
                StunResult.Error("stun-a", "timeout"),
                StunResult.Success("stun-b", "203.0.113.10", 40000)
            )
        )
        val result = NatDetector(stun, listOf("stun-a" to 3478, "stun-b" to 3478)).detect()

        assertEquals("203.0.113.10", result.publicIp)
        assertEquals(40000, result.publicPort)
        assertEquals("stun-b", result.stunServer)
        assertEquals(NatType.UNKNOWN, result.natType)
    }

    @Test
    fun `detect marks stable public port`() {
        val stun = FakeStunProbe(
            listOf(
                StunResult.Success("stun-a", "203.0.113.10", 40000),
                StunResult.Success("stun-b", "203.0.113.10", 40000)
            )
        )
        val result = NatDetector(stun, listOf("stun-a" to 3478, "stun-b" to 3478)).detect()

        assertTrue(result.portStable)
    }

    @Test
    fun `available UDP port honors free preferred port`() {
        val preferred = detector.findAvailableUdpPort(0)

        val selected = detector.findAvailableUdpPort(preferred)

        assertEquals(preferred, selected)
    }

    @Test
    fun `available TCP port honors free preferred port`() {
        val preferred = detector.findAvailableTcpPort(0)

        val selected = detector.findAvailableTcpPort(preferred)

        assertEquals(preferred, selected)
    }

    private class FakeStunProbe(private val results: List<StunResult>) : StunProbe {
        private var index = 0

        override fun query(serverHost: String, serverPort: Int, localPort: Int, timeoutMs: Int): StunResult {
            return results.getOrNull(index++) ?: StunResult.Error(serverHost, "no response")
        }
    }
}
