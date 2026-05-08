package com.kirivsoft.directlink

import com.kirivsoft.directlink.network.HolePunchingManager
import com.kirivsoft.directlink.network.NatDetectionResult
import com.kirivsoft.directlink.network.NatDetector
import com.kirivsoft.directlink.network.PeerEndpoint
import com.kirivsoft.directlink.network.PunchResult
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
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.UUID

class NetworkPeer(
    private val config: PeerConfig,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val serializer: DlpSerializer = DlpSerializer(),
    private val natDetector: NatDetector = NatDetector(),
    private val holePunchingManager: HolePunchingManager = HolePunchingManager()
) {
    private val _state = MutableStateFlow(NetworkPeerState())
    val state: StateFlow<NetworkPeerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PeerEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<PeerEvent> = _events.asSharedFlow()

    private var natResult: NatDetectionResult? = null
    private var importedPeerName: String? = null
    private var remoteEndpoint: PeerEndpoint? = null
    private val fingerprint = UUID.nameUUIDFromBytes(config.deviceUuid.toByteArray()).toString().take(8)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        _state.update { it.copy(phase = PeerPhase.GatheringNetwork) }
        val nat = detectNat()
        _state.update {
            it.copy(
                phase = PeerPhase.Ready(
                    publicIp = nat.publicIp,
                    localIp = nat.localIp,
                    udpPort = nat.localPort,
                    tcpPort = nat.tcpPort,
                    natType = nat.natType,
                    fingerprint = fingerprint
                )
            )
        }
    }

    suspend fun generateDlpPacket(password: String, outputDir: File? = config.fileSaveDir): File = withContext(Dispatchers.IO) {
        val nat = detectNat()
        val dir = outputDir ?: createTempDir("directlink_")
        dir.mkdirs()
        val file = File(dir, "directlink_${System.currentTimeMillis()}.dlp")
        val packet = serializer.buildPacket(
            deviceName = config.deviceName,
            deviceUuid = config.deviceUuid,
            platform = config.platform,
            appVersion = config.appVersion,
            fingerprint = fingerprint,
            publicIp = nat.publicEndpointIp(),
            publicPort = nat.publicPort,
            natType = nat.natType,
            password = password,
            ttlSeconds = config.packetTtlSeconds
        )
        serializer.write(packet, password, file)
        _state.update { it.copy(phase = PeerPhase.PacketGenerated(file, fingerprint, packet.expiresAt)) }
        _events.emit(PeerEvent.DlpPacketReady(file, fingerprint))
        file
    }

    suspend fun importDlpPacket(file: File, password: String): String? = withContext(Dispatchers.IO) {
        when (val result = serializer.parse(file, password)) {
            is DlpParseResult.Success -> {
                importedPeerName = result.packet.deviceName
                remoteEndpoint = PeerEndpoint(
                    publicIp = result.packet.publicIp,
                    publicPort = result.packet.publicPort,
                    natType = result.packet.natType
                )
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
        val nat = detectNat()
        val endpoint = remoteEndpoint ?: run {
            _state.update { it.copy(phase = PeerPhase.Error("Import a DLP packet before connecting")) }
            return@withContext
        }

        if (isSelfEndpoint(endpoint, nat)) {
            _state.update { it.copy(phase = PeerPhase.Connected(peerName, System.currentTimeMillis(), 0)) }
            return@withContext
        }

        val punch = runCatching {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(nat.localPort))
                holePunchingManager.punch(
                    localSocket = socket,
                    localNatType = nat.natType,
                    remote = endpoint,
                    timeoutMs = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            }
        }.getOrElse { error ->
            _state.update { it.copy(phase = PeerPhase.Error("Connection failed: ${error.message}")) }
            return@withContext
        }

        when (punch) {
            is PunchResult.Success -> _state.update {
                it.copy(phase = PeerPhase.Connected(peerName, System.currentTimeMillis(), punch.rttMs))
            }
            is PunchResult.NeedsRelay -> _state.update {
                it.copy(phase = PeerPhase.Error("Relay is required for this NAT pair: ${punch.reason}"))
            }
            is PunchResult.Failed -> _state.update {
                it.copy(phase = PeerPhase.Error("Connection failed: ${punch.reason}"))
            }
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
        natResult = null
        importedPeerName = null
        remoteEndpoint = null
        _state.update { it.copy(phase = PeerPhase.Idle) }
    }

    private fun detectNat(): NatDetectionResult = natResult ?: natDetector
        .detect(config.preferredUdpPort, config.preferredTcpPort)
        .also { natResult = it }

    private fun NatDetectionResult.publicEndpointIp(): String =
        publicIp.takeUnless { it == "0.0.0.0" } ?: localIp

    private fun isSelfEndpoint(endpoint: PeerEndpoint, nat: NatDetectionResult): Boolean =
        endpoint.publicPort == nat.publicPort && (endpoint.publicIp == nat.publicIp || endpoint.publicIp == nat.localIp)

    private fun failImport(reason: String): String? {
        _state.update { it.copy(phase = PeerPhase.Error(reason)) }
        return null
    }
}
