package com.kirivsoft.directlink.relay

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayProtocolTest {
    @Test
    fun `relay payload frame round trips through codec`() {
        val frame = RelayFrame.Payload(
            sessionId = "session-1",
            fromPeerId = "alice",
            toPeerId = "bob",
            bytes = "hello relay".toByteArray()
        )

        val decoded = RelayFrameCodec.decode(RelayFrameCodec.encode(frame))

        assertTrue(decoded is RelayFrame.Payload)
        decoded as RelayFrame.Payload
        assertEquals("session-1", decoded.sessionId)
        assertEquals("alice", decoded.fromPeerId)
        assertEquals("bob", decoded.toPeerId)
        assertArrayEquals("hello relay".toByteArray(), decoded.bytes)
    }

    @Test
    fun `relay codec ignores malformed frame`() {
        assertNull(RelayFrameCodec.decode("not-json"))
    }

    @Test
    fun `host registers relay session`() {
        val server = RelayServerCoordinator { "session-1" }
        val host = RelayClientHandshake(
            peerId = "alice",
            token = "shared-token",
            role = RelayHandshakeRole.Host
        )

        val response = server.handle(host.start()).single()
        host.handle(response)

        assertEquals(RelayHandshakeState.Ready, host.state)
        assertEquals("session-1", host.sessionId)
    }

    @Test
    fun `guest joins relay session`() {
        val server = RelayServerCoordinator { "session-1" }
        val host = RelayClientHandshake("alice", "shared-token", RelayHandshakeRole.Host)
        host.handle(server.handle(host.start()).single())
        val guest = RelayClientHandshake(
            peerId = "bob",
            token = "shared-token",
            role = RelayHandshakeRole.Guest,
            requestedSessionId = "session-1"
        )

        val response = server.handle(guest.start()).single()
        guest.handle(response)

        assertEquals(RelayHandshakeState.Ready, guest.state)
        assertEquals("session-1", guest.sessionId)
    }

    @Test
    fun `server routes payload between joined peers`() {
        val server = RelayServerCoordinator { "session-1" }
        val host = RelayClientHandshake("alice", "shared-token", RelayHandshakeRole.Host)
        val guest = RelayClientHandshake("bob", "shared-token", RelayHandshakeRole.Guest, "session-1")
        host.handle(server.handle(host.start()).single())
        guest.handle(server.handle(guest.start()).single())
        val payload = RelayFrame.Payload(
            sessionId = "session-1",
            fromPeerId = "alice",
            toPeerId = "bob",
            bytes = byteArrayOf(1, 2, 3)
        )

        val routed = server.handle(payload).single()

        assertEquals(payload, routed)
    }

    @Test
    fun `server rejects guest with wrong token`() {
        val server = RelayServerCoordinator { "session-1" }
        val host = RelayClientHandshake("alice", "shared-token", RelayHandshakeRole.Host)
        host.handle(server.handle(host.start()).single())
        val guest = RelayClientHandshake(
            peerId = "bob",
            token = "wrong-token",
            role = RelayHandshakeRole.Guest,
            requestedSessionId = "session-1"
        )

        val response = server.handle(guest.start()).single()
        guest.handle(response)

        assertTrue(response is RelayFrame.Error)
        assertEquals(RelayHandshakeState.Failed, guest.state)
    }
}
