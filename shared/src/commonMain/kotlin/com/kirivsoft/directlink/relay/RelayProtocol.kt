package com.kirivsoft.directlink.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

sealed class RelayFrame {
    data class Register(val peerId: String, val token: String) : RelayFrame()
    data class Registered(val sessionId: String) : RelayFrame()
    data class Join(val sessionId: String, val peerId: String, val token: String) : RelayFrame()
    data class Joined(val sessionId: String, val peerId: String) : RelayFrame()
    data class Payload(
        val sessionId: String,
        val fromPeerId: String,
        val toPeerId: String,
        val bytes: ByteArray
    ) : RelayFrame() {
        override fun equals(other: Any?): Boolean = other is Payload &&
            sessionId == other.sessionId &&
            fromPeerId == other.fromPeerId &&
            toPeerId == other.toPeerId &&
            bytes.contentEquals(other.bytes)

        override fun hashCode(): Int =
            31 * (31 * (31 * sessionId.hashCode() + fromPeerId.hashCode()) + toPeerId.hashCode()) + bytes.contentHashCode()
    }
    data class Close(val sessionId: String, val reason: String) : RelayFrame()
    data class Error(val reason: String) : RelayFrame()
}

object RelayFrameCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(frame: RelayFrame): String = json.encodeToString(RelayEnvelope.serializer(), frame.toEnvelope())

    fun decode(text: String): RelayFrame? = runCatching {
        json.decodeFromString(RelayEnvelope.serializer(), text).toFrame()
    }.getOrNull()

    private fun RelayFrame.toEnvelope(): RelayEnvelope = when (this) {
        is RelayFrame.Register -> RelayEnvelope(type = TYPE_REGISTER, peerId = peerId, token = token)
        is RelayFrame.Registered -> RelayEnvelope(type = TYPE_REGISTERED, sessionId = sessionId)
        is RelayFrame.Join -> RelayEnvelope(type = TYPE_JOIN, sessionId = sessionId, peerId = peerId, token = token)
        is RelayFrame.Joined -> RelayEnvelope(type = TYPE_JOINED, sessionId = sessionId, peerId = peerId)
        is RelayFrame.Payload -> RelayEnvelope(
            type = TYPE_PAYLOAD,
            sessionId = sessionId,
            fromPeerId = fromPeerId,
            toPeerId = toPeerId,
            payloadBase64 = Base64.getEncoder().encodeToString(bytes)
        )
        is RelayFrame.Close -> RelayEnvelope(type = TYPE_CLOSE, sessionId = sessionId, reason = reason)
        is RelayFrame.Error -> RelayEnvelope(type = TYPE_ERROR, reason = reason)
    }

    private fun RelayEnvelope.toFrame(): RelayFrame? = when (type) {
        TYPE_REGISTER -> RelayFrame.Register(peerId ?: return null, token ?: return null)
        TYPE_REGISTERED -> RelayFrame.Registered(sessionId ?: return null)
        TYPE_JOIN -> RelayFrame.Join(sessionId ?: return null, peerId ?: return null, token ?: return null)
        TYPE_JOINED -> RelayFrame.Joined(sessionId ?: return null, peerId ?: return null)
        TYPE_PAYLOAD -> RelayFrame.Payload(
            sessionId = sessionId ?: return null,
            fromPeerId = fromPeerId ?: return null,
            toPeerId = toPeerId ?: return null,
            bytes = Base64.getDecoder().decode(payloadBase64 ?: return null)
        )
        TYPE_CLOSE -> RelayFrame.Close(sessionId ?: return null, reason ?: "closed")
        TYPE_ERROR -> RelayFrame.Error(reason ?: "relay error")
        else -> null
    }

    private const val TYPE_REGISTER = "register"
    private const val TYPE_REGISTERED = "registered"
    private const val TYPE_JOIN = "join"
    private const val TYPE_JOINED = "joined"
    private const val TYPE_PAYLOAD = "payload"
    private const val TYPE_CLOSE = "close"
    private const val TYPE_ERROR = "error"
}

@Serializable
private data class RelayEnvelope(
    val type: String,
    val sessionId: String? = null,
    val peerId: String? = null,
    val token: String? = null,
    val fromPeerId: String? = null,
    val toPeerId: String? = null,
    val payloadBase64: String? = null,
    val reason: String? = null
)
