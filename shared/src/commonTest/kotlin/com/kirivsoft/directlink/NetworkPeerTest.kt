package com.kirivsoft.directlink

import com.kirivsoft.directlink.network.HolePunchingManager
import com.kirivsoft.directlink.network.NatDetectionResult
import com.kirivsoft.directlink.network.NatDetector
import com.kirivsoft.directlink.network.NatType
import com.kirivsoft.directlink.network.PeerEndpoint
import com.kirivsoft.directlink.network.PunchResult
import com.kirivsoft.directlink.packet.DlpSerializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.DatagramSocket

class NetworkPeerTest {
    @Test
    fun `initialize moves peer to ready`() = runTest {
        val peer = NetworkPeer(testConfig())

        peer.initialize()

        assertTrue(peer.state.value.phase is PeerPhase.Ready)
    }

    @Test
    fun `generate import and connect packet`() = runTest {
        val dir = createTempDir("directlink_test_")
        val peer = NetworkPeer(testConfig(fileSaveDir = dir))

        peer.initialize()
        val packet = peer.generateDlpPacket("pass", dir)
        val imported = peer.importDlpPacket(packet, "pass")
        peer.connect()

        assertTrue(packet.exists())
        assertNotNull(imported)
        assertTrue(peer.state.value.phase is PeerPhase.Connected)
        dir.deleteRecursively()
    }

    @Test
    fun `connect moves to relay required when relay is configured`() = runTest {
        val dir = createTempDir("directlink_test_")
        val natDetector = mockk<NatDetector>()
        val punchingManager = mockk<HolePunchingManager>()
        every { natDetector.detect(any(), any()) } returns NatDetectionResult(
            publicIp = "198.51.100.10",
            publicPort = 44_000,
            localIp = "192.168.1.10",
            localPort = 44_000,
            tcpPort = 44_001,
            natType = NatType.SYMMETRIC,
            stunServer = "test",
            portStable = false
        )
        every {
            punchingManager.punch(any<DatagramSocket>(), any(), any<PeerEndpoint>(), any())
        } returns PunchResult.NeedsRelay("test relay path")
        val peer = NetworkPeer(
            config = testConfig(fileSaveDir = dir, relayUrl = "wss://relay.example/directlink"),
            natDetector = natDetector,
            holePunchingManager = punchingManager
        )
        val packet = remotePacket(dir)

        peer.importDlpPacket(packet, "pass")
        peer.connect()

        val phase = peer.state.value.phase
        assertTrue(phase is PeerPhase.RelayRequired)
        phase as PeerPhase.RelayRequired
        assertEquals("wss://relay.example/directlink", phase.relayUrl)
        assertTrue(phase.reason.contains("Relay is required"))
        dir.deleteRecursively()
    }

    @Test
    fun `import rejects invalid password`() = runTest {
        val dir = createTempDir("directlink_test_")
        val peer = NetworkPeer(testConfig(fileSaveDir = dir))

        peer.initialize()
        val packet = peer.generateDlpPacket("pass", dir)
        val imported = peer.importDlpPacket(packet, "wrong")

        assertNull(imported)
        assertTrue(peer.state.value.phase is PeerPhase.Error)
        dir.deleteRecursively()
    }

    private fun remotePacket(dir: File): File {
        val serializer = DlpSerializer()
        val file = File(dir, "remote.dlp")
        val packet = serializer.buildPacket(
            deviceName = "Remote Device",
            deviceUuid = "remote-device-uuid",
            platform = "JUnit",
            appVersion = "0.1.0",
            fingerprint = "remote01",
            publicIp = "203.0.113.20",
            publicPort = 45_000,
            natType = NatType.SYMMETRIC,
            password = "pass",
            ttlSeconds = 60
        )
        serializer.write(packet, "pass", file)
        return file
    }

    private fun testConfig(fileSaveDir: File? = null, relayUrl: String? = null) = PeerConfig(
        deviceName = "Test Device",
        deviceUuid = "test-device-uuid",
        platform = "JUnit",
        fileSaveDir = fileSaveDir,
        relayUrl = relayUrl
    )
}
