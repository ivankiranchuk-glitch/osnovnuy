package com.kirivsoft.directlink.relay

import java.util.UUID

enum class RelayHandshakeRole {
    Host,
    Guest
}

enum class RelayHandshakeState {
    Idle,
    WaitingForRelay,
    Ready,
    Failed
}

class RelayClientHandshake(
    private val peerId: String,
    private val token: String,
    private val role: RelayHandshakeRole,
    private val requestedSessionId: String? = null
) {
    var state: RelayHandshakeState = RelayHandshakeState.Idle
        private set
    var sessionId: String? = null
        private set
    var failureReason: String? = null
        private set

    fun start(): RelayFrame {
        state = RelayHandshakeState.WaitingForRelay
        return when (role) {
            RelayHandshakeRole.Host -> RelayFrame.Register(peerId, token)
            RelayHandshakeRole.Guest -> RelayFrame.Join(
                sessionId = requestedSessionId ?: error("Guest relay handshake requires a session id"),
                peerId = peerId,
                token = token
            )
        }
    }

    fun handle(frame: RelayFrame): RelayFrame? {
        return when (frame) {
            is RelayFrame.Registered -> handleRegistered(frame)
            is RelayFrame.Joined -> handleJoined(frame)
            is RelayFrame.Error -> fail(frame.reason)
            else -> null
        }
    }

    private fun handleRegistered(frame: RelayFrame.Registered): RelayFrame? {
        if (role != RelayHandshakeRole.Host) return fail("Guest received relay registration")
        sessionId = frame.sessionId
        state = RelayHandshakeState.Ready
        return null
    }

    private fun handleJoined(frame: RelayFrame.Joined): RelayFrame? {
        if (role != RelayHandshakeRole.Guest) return fail("Host received relay join acknowledgement")
        sessionId = frame.sessionId
        state = RelayHandshakeState.Ready
        return null
    }

    private fun fail(reason: String): RelayFrame? {
        failureReason = reason
        state = RelayHandshakeState.Failed
        return null
    }
}

class RelayServerCoordinator(
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() }
) {
    private val sessions = mutableMapOf<String, RelaySession>()

    fun handle(frame: RelayFrame): List<RelayFrame> = when (frame) {
        is RelayFrame.Register -> register(frame)
        is RelayFrame.Join -> join(frame)
        is RelayFrame.Payload -> route(frame)
        is RelayFrame.Close -> close(frame)
        else -> listOf(RelayFrame.Error("Unsupported relay frame"))
    }

    private fun register(frame: RelayFrame.Register): List<RelayFrame> {
        val sessionId = sessionIdFactory()
        sessions[sessionId] = RelaySession(
            sessionId = sessionId,
            token = frame.token,
            peers = mutableSetOf(frame.peerId)
        )
        return listOf(RelayFrame.Registered(sessionId))
    }

    private fun join(frame: RelayFrame.Join): List<RelayFrame> {
        val session = sessions[frame.sessionId]
            ?: return listOf(RelayFrame.Error("Unknown relay session"))
        if (session.token != frame.token) {
            return listOf(RelayFrame.Error("Relay token mismatch"))
        }
        session.peers += frame.peerId
        return listOf(RelayFrame.Joined(frame.sessionId, frame.peerId))
    }

    private fun route(frame: RelayFrame.Payload): List<RelayFrame> {
        val session = sessions[frame.sessionId]
            ?: return listOf(RelayFrame.Error("Unknown relay session"))
        if (frame.fromPeerId !in session.peers || frame.toPeerId !in session.peers) {
            return listOf(RelayFrame.Error("Relay payload references an unknown peer"))
        }
        return listOf(frame)
    }

    private fun close(frame: RelayFrame.Close): List<RelayFrame> {
        sessions.remove(frame.sessionId)
        return listOf(frame)
    }

    private data class RelaySession(
        val sessionId: String,
        val token: String,
        val peers: MutableSet<String>
    )
}
