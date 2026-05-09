package com.kirivsoft.directlink.relay

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TcpRelayTransportTest {
    @Test
    fun `TCP relay server registers joins and routes payload`() {
        TcpRelayServer(coordinator = RelayServerCoordinator { "session-1" }).use { server ->
            server.start()
            TcpRelayClient("127.0.0.1", server.port).use { host ->
                TcpRelayClient("127.0.0.1", server.port).use { guest ->
                    val hostHandshake = RelayClientHandshake("alice", "shared-token", RelayHandshakeRole.Host)
                    host.send(hostHandshake.start())
                    val registered = host.receive()
                    assertTrue(registered is RelayFrame.Registered)
                    hostHandshake.handle(registered as RelayFrame.Registered)
                    assertEquals(RelayHandshakeState.Ready, hostHandshake.state)

                    val guestHandshake = RelayClientHandshake(
                        peerId = "bob",
                        token = "shared-token",
                        role = RelayHandshakeRole.Guest,
                        requestedSessionId = "session-1"
                    )
                    guest.send(guestHandshake.start())
                    val joined = guest.receive()
                    assertTrue(joined is RelayFrame.Joined)
                    guestHandshake.handle(joined as RelayFrame.Joined)
                    assertEquals(RelayHandshakeState.Ready, guestHandshake.state)

                    host.send(
                        RelayFrame.Payload(
                            sessionId = "session-1",
                            fromPeerId = "alice",
                            toPeerId = "bob",
                            bytes = byteArrayOf(9, 8, 7)
                        )
                    )
                    val delivered = guest.receive()

                    assertTrue(delivered is RelayFrame.Payload)
                    delivered as RelayFrame.Payload
                    assertEquals("alice", delivered.fromPeerId)
                    assertEquals("bob", delivered.toPeerId)
                    assertArrayEquals(byteArrayOf(9, 8, 7), delivered.bytes)
                }
            }
        }
    }

    @Test
    fun `TCP relay server rejects payload to disconnected peer`() {
        TcpRelayServer(coordinator = RelayServerCoordinator { "session-1" }).use { server ->
            server.start()
            TcpRelayClient("127.0.0.1", server.port).use { host ->
                val hostHandshake = RelayClientHandshake("alice", "shared-token", RelayHandshakeRole.Host)
                host.send(hostHandshake.start())
                hostHandshake.handle(host.receive() as RelayFrame.Registered)

                host.send(
                    RelayFrame.Payload(
                        sessionId = "session-1",
                        fromPeerId = "alice",
                        toPeerId = "bob",
                        bytes = byteArrayOf(1)
                    )
                )
                val response = host.receive()

                assertTrue(response is RelayFrame.Error)
            }
        }
    }
}
