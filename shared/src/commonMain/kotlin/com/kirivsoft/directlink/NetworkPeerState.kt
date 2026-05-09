package com.kirivsoft.directlink

import com.kirivsoft.directlink.network.NatType
import java.io.File

sealed class PeerPhase {
    object Idle : PeerPhase()
    object GatheringNetwork : PeerPhase()
    data class Ready(
        val publicIp: String,
        val localIp: String,
        val udpPort: Int,
        val tcpPort: Int,
        val natType: NatType,
        val fingerprint: String
    ) : PeerPhase()
    data class PacketGenerated(
        val dlpFile: File,
        val fingerprint: String,
        val expiresAt: Long
    ) : PeerPhase()
    data class AwaitingConnection(val peerName: String) : PeerPhase()
    data class Connected(val peerName: String, val sessionId: Long, val rttMs: Long) : PeerPhase()
    data class RelayRequired(val peerName: String, val relayUrl: String?, val reason: String) : PeerPhase()
    data class Error(val reason: String, val recoverable: Boolean = true) : PeerPhase()
}

data class NetworkPeerState(
    val phase: PeerPhase = PeerPhase.Idle,
    val sentMessages: Int = 0,
    val receivedMessages: Int = 0,
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0
) {
    val isConnected: Boolean get() = phase is PeerPhase.Connected
}

sealed class PeerEvent {
    data class DlpPacketReady(val file: File, val fingerprint: String) : PeerEvent()
    data class IncomingText(val text: String, val msgId: Long) : PeerEvent()
    data class IncomingFile(
        val name: String,
        val sizeBytes: Long,
        val savedPath: String,
        val sha256: ByteArray
    ) : PeerEvent()
    data class FileTransferProgress(
        val direction: FileTransferDirection,
        val name: String,
        val completedBytes: Long,
        val totalBytes: Long
    ) : PeerEvent() {
        val percent: Int get() = if (totalBytes <= 0L) 100 else ((completedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        val isComplete: Boolean get() = totalBytes == 0L || completedBytes >= totalBytes
    }
    data class SendFailed(val reason: String) : PeerEvent()
    data class ConnectionLost(val reason: String) : PeerEvent()
}

enum class FileTransferDirection {
    Sending,
    Receiving
}

data class PeerConfig(
    val deviceName: String,
    val deviceUuid: String,
    val platform: String,
    val appVersion: String = "0.1.0",
    val preferredUdpPort: Int = 0,
    val preferredTcpPort: Int = 0,
    val packetTtlSeconds: Long = 86_400,
    val fileSaveDir: File? = null,
    val relayUrl: String? = null
)
