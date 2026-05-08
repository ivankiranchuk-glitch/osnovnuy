package com.kirivsoft.directlink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class NetworkPeer(
    private val config: PeerConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val _state = MutableStateFlow(NetworkPeerState())
    val state: StateFlow<NetworkPeerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PeerEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<PeerEvent> = _events.asSharedFlow()

    private var importedPeerName: String? = null
    private val fingerprint = UUID.nameUUIDFromBytes(config.deviceUuid.toByteArray()).toString().take(8)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        _state.update { it.copy(phase = PeerPhase.GatheringNetwork) }
        val udpPort = if (config.preferredUdpPort > 0) config.preferredUdpPort else 49152
        val tcpPort = if (config.preferredTcpPort > 0) config.preferredTcpPort else 49153
        _state.update {
            it.copy(
                phase = PeerPhase.Ready(
                    publicIp = "0.0.0.0",
                    localIp = "127.0.0.1",
                    udpPort = udpPort,
                    tcpPort = tcpPort,
                    natType = "UNKNOWN",
                    fingerprint = fingerprint
                )
            )
        }
    }

    suspend fun generateDlpPacket(password: String, outputDir: File? = config.fileSaveDir): File = withContext(Dispatchers.IO) {
        val dir = outputDir ?: createTempDir("directlink_")
        dir.mkdirs()
        val file = File(dir, "directlink_${System.currentTimeMillis()}.dlp")
        val expiresAt = System.currentTimeMillis() / 1000 + config.packetTtlSeconds
        file.writeText(
            listOf(
                "DirectLink MVP packet",
                "device=${config.deviceName}",
                "uuid=${config.deviceUuid}",
                "platform=${config.platform}",
                "fingerprint=$fingerprint",
                "expiresAt=$expiresAt",
                "passwordHash=${sha256(password.toByteArray()).joinToString("") { b -> "%02x".format(b) }}"
            ).joinToString("\n")
        )
        _state.update { it.copy(phase = PeerPhase.PacketGenerated(file, fingerprint, expiresAt)) }
        _events.emit(PeerEvent.DlpPacketReady(file, fingerprint))
        file
    }

    suspend fun importDlpPacket(file: File, password: String): String? = withContext(Dispatchers.IO) {
        val text = runCatching { file.readText() }.getOrElse {
            _state.update { state -> state.copy(phase = PeerPhase.Error("Cannot read DLP packet: ${it.message}")) }
            return@withContext null
        }
        val name = text.lineSequence()
            .firstOrNull { it.startsWith("device=") }
            ?.removePrefix("device=")
            ?: file.nameWithoutExtension
        importedPeerName = name
        _state.update { it.copy(phase = PeerPhase.AwaitingConnection(name)) }
        name
    }

    suspend fun connect(timeoutMs: Long = 30_000L) = withContext(Dispatchers.IO) {
        val peerName = importedPeerName ?: "DirectLink peer"
        _state.update {
            it.copy(phase = PeerPhase.Connected(peerName, System.currentTimeMillis(), 0))
        }
    }

    suspend fun sendText(text: String) {
        if (!state.value.isConnected) {
            _events.emit(PeerEvent.SendFailed("Session is not connected"))
            return
        }
        _state.update {
            it.copy(sentMessages = it.sentMessages + 1, sentBytes = it.sentBytes + text.toByteArray().size)
        }
    }

    suspend fun sendFile(file: File) {
        if (!state.value.isConnected) {
            _events.emit(PeerEvent.SendFailed("Session is not connected"))
            return
        }
        val bytes = file.readBytes()
        _state.update { it.copy(sentMessages = it.sentMessages + 1, sentBytes = it.sentBytes + bytes.size) }
    }

    fun close() {
        importedPeerName = null
        _state.update { it.copy(phase = PeerPhase.Idle) }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
