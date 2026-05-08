package com.kirivsoft.directlink.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NatDetectorTest {
    private val detector = NatDetector()

    @Test
    fun `detect returns unknown NAT placeholder`() {
        val result = detector.detect()

        assertEquals(NatType.UNKNOWN, result.natType)
        assertTrue(result.localPort > 0)
        assertTrue(result.tcpPort > 0)
        assertTrue(result.localIp.isNotBlank())
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
}
