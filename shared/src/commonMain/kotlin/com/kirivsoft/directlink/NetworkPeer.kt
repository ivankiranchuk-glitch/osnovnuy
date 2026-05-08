package com.kirivsoft.directlink

import com.kirivsoft.directlink.packet.DlpParseResult
import com.kirivsoft.directlink.packet.DlpSerializer
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
import java.util.UUID

class NetworkPeer(
    private val config: PeerConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val serializer: DlpSerializer = DlpSerializer()
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
        val packet = serializer.buildPacket(
            deviceName = config.deviceName,
            deviceUuid = config.deviceUuid,
            platform = config.platform,
            appVersion = config.appVersion,
            fingerprint = fingerprint,
            password = password,
            ttlSeconds = config.packetTtlSeconds
        )
        serializer.write(packet, file)
        _state.update { it.copy(phase = PeerPhase.PacketGenerated(file, fingerprint, packet.expiresAt)) }
        _events.emit(PeerEvent.DlpPacketReady(file, fingerprint))
        file
    }

    suspend fun importDlpPacket(file: File, password: String): String? = withContext(Dispatchers.IO) {
        when (val result = serializer.parse(file, password)) {
            is DlpParseResult.Success -> {
                importedPeerName = result.packet.deviceName
                _state.update { it.copy(phase = PeerPhase.AwaitingConnection(result.packet.deviceName)) }
                result.packet.deviceName
            }
            is DlpParseResult.InvalidPassword -> failImport(result.reason)
            is DlpParseResult.Expired -> failImport("DLP packet expired")
            is DlpParseResult.Malformed -> failImport(result.reason)
            is DlpParseResult.UnsupportedVersion -> failImport("Unsupported DLP version: ${result.found}")
        }
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

    private fun failImport(reason: String): String? {
        _state.update { it.copy(phase = PeerPhase.Error(reason)) }
        return null
    }
}
