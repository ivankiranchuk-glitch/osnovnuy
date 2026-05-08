package com.kirivsoft.directlink

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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

    private fun testConfig(fileSaveDir: File? = null) = PeerConfig(
        deviceName = "Test Device",
        deviceUuid = "test-device-uuid",
        platform = "JUnit",
        fileSaveDir = fileSaveDir
    )
}
