package com.kirivsoft.directlink

import com.kirivsoft.directlink.network.NatType
import com.kirivsoft.directlink.packet.DlpSerializer
import com.kirivsoft.directlink.relay.RelayClientHandshake
import com.kirivsoft.directlink.relay.RelayFrame
import com.kirivsoft.directlink.relay.RelayHandshakeRole
import com.kirivsoft.directlink.relay.RelayServerCoordinator
import com.kirivsoft.directlink.relay.TcpRelayClient
import com.kirivsoft.directlink.relay.TcpRelayServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RelayNetworkPeerTest {
    @Test
    fun `network peer can host a relay session`() = runTest {
        val dir = createTempDir("directlink_relay_test_")
        TcpRelayServer(coordinator = RelayServerCoordinator { "session-1" }).use { server ->
            server.start()
            val peer = NetworkPeer(testConfig(fileSaveDir = dir))
            val packet = remotePacket(dir)

            peer.importDlpPacket(packet, "pass")
            peer.connectViaRelay("tcp://127.0.0.1:${server.port}", RelayHandshakeRole.Host)

            val phase = peer.state.value.phase
            assertTrue(phase is PeerPhase.RelayConnected)
            phase as PeerPhase.RelayConnected
            assertEquals("Remote Device", phase.peerName)
            assertEquals("tcp://127.0.0.1:${server.port}", phase.relayUrl)
            assertEquals("session-1", phase.relaySessionId)
            assertTrue(peer.state.value.isConnected)
            peer.close()
        }
        dir.deleteRecursively()
    }

    @Test
    fun `network peer can join an existing relay session`() = runTest {
        val dir = createTempDir("directlink_relay_test_")
        TcpRelayServer(coordinator = RelayServerCoordinator { "session-1" }).use { server ->
            server.start()
            TcpRelayClient("127.0.0.1", server.port).use { host ->
                val hostHandshake = RelayClientHandshake(
                    peerId = "host-device",
                    token = "pass",
                    role = RelayHandshakeRole.Host
                )
                host.send(hostHandshake.start())
                val registered = host.receive()
                assertTrue(registered is RelayFrame.Registered)
                hostHandshake.handle(registered as RelayFrame.Registered)

                val peer = NetworkPeer(testConfig(fileSaveDir = dir))
                val packet = remotePacket(dir)

                peer.importDlpPacket(packet, "pass")
                peer.connectViaRelay(
                    relayUrl = "tcp://127.0.0.1:${server.port}",
                    role = RelayHandshakeRole.Guest,
                    sessionId = "session-1"
                )

                val phase = peer.state.value.phase
                assertTrue(phase is PeerPhase.RelayConnected)
                phase as PeerPhase.RelayConnected
                assertEquals("session-1", phase.relaySessionId)
                assertTrue(peer.state.value.isConnected)
                peer.close()
            }
        }
        dir.deleteRecursively()
    }

    private fun remotePacket(dir: File): File {
        val serializer = DlpSerializer()
        val file = File(dir, "remote-relay.dlp")
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

    private fun testConfig(fileSaveDir: File? = null) = PeerConfig(
        deviceName = "Test Device",
        deviceUuid = "test-device-uuid",
        platform = "JUnit",
        fileSaveDir = fileSaveDir
    )
}
