package com.kirivsoft.directlink

import com.kirivsoft.directlink.network.HolePunchingManager
import com.kirivsoft.directlink.network.NatDetectionResult
import com.kirivsoft.directlink.network.NatDetector
import com.kirivsoft.directlink.network.PeerEndpoint
import com.kirivsoft.directlink.network.PunchResult
import com.kirivsoft.directlink.packet.DlpParseResult
import com.kirivsoft.directlink.packet.DlpSerializer
import com.kirivsoft.directlink.tunnel.FileChunk
import com.kirivsoft.directlink.tunnel.FileEnd
import com.kirivsoft.directlink.tunnel.FileStart
import com.kirivsoft.directlink.tunnel.FileTransferProgress
import com.kirivsoft.directlink.tunnel.TunnelCipher
import com.kirivsoft.directlink.tunnel.UdpTunnelSession
import com.kirivsoft.directlink.tunnel.sha256
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
import java.util.concurrent.ConcurrentHashMap

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
    private var tunnelPassword: String? = null
    private var tunnelSession: UdpTunnelSession? = null
    private val incomingFiles = ConcurrentHashMap<Long, IncomingFileAssembly>()
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
        tunnelPassword = password
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
                tunnelPassword = password
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

        closeTunnelOnly()
        if (isSelfEndpoint(endpoint, nat)) {
            _state.update { it.copy(phase = PeerPhase.Connected(peerName, System.currentTimeMillis(), 0)) }
            return@withContext
        }

        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(nat.localPort))
        }
        val punch = runCatching {
            holePunchingManager.punch(
                localSocket = socket,
                localNatType = nat.natType,
                remote = endpoint,
                timeoutMs = timeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        }.getOrElse { error ->
            socket.close()
            _state.update { it.copy(phase = PeerPhase.Error("Connection failed: ${error.message}")) }
            return@withContext
        }

        when (punch) {
            is PunchResult.Success -> {
                tunnelSession = UdpTunnelSession(
                    socket = socket,
                    remoteAddress = punch.remoteAddress,
                    scope = scope,
                    onText = ::handleIncomingText,
                    onFileStart = ::handleFileStart,
                    onFileChunk = ::handleFileChunk,
                    onFileEnd = ::handleFileEnd,
                    onFileSendProgress = ::handleFileSendProgress,
                    cipher = tunnelPassword?.let(TunnelCipher::fromPassword),
                    onClosed = { reason ->
                        _state.update { it.copy(phase = PeerPhase.Error(reason)) }
                        _events.tryEmit(PeerEvent.ConnectionLost(reason))
                    }
                ).also { it.start() }
                _state.update { it.copy(phase = PeerPhase.Connected(peerName, System.currentTimeMillis(), punch.rttMs)) }
            }
            is PunchResult.NeedsRelay -> {
                socket.close()
                _state.update { it.copy(phase = PeerPhase.Error("Relay is required for this NAT pair: ${punch.reason}")) }
            }
            is PunchResult.Failed -> {
                socket.close()
                _state.update { it.copy(phase = PeerPhase.Error("Connection failed: ${punch.reason}")) }
            }
        }
    }

    suspend fun sendText(text: String) {
        if (!state.value.isConnected) {
            _events.emit(PeerEvent.SendFailed("Session is not connected"))
            return
        }
        val bytes = text.toByteArray().size
        val sent = runCatching { tunnelSession?.sendText(text) }.getOrElse { error ->
            _events.emit(PeerEvent.SendFailed(error.message ?: "Text send failed"))
            return
        }
        if (sent == null) {
            _events.emit(PeerEvent.SendFailed("UDP tunnel is not ready"))
            return
        }
        _state.update { it.copy(sentMessages = it.sentMessages + 1, sentBytes = it.sentBytes + bytes) }
    }

    suspend fun sendFile(file: File) {
        if (!state.value.isConnected) {
            _events.emit(PeerEvent.SendFailed("Session is not connected"))
            return
        }
        val bytes = runCatching { file.readBytes() }.getOrElse { error ->
            _events.emit(PeerEvent.SendFailed(error.message ?: "Cannot read file"))
            return
        }
        val sent = runCatching { tunnelSession?.sendFile(file.name, bytes) }.getOrElse { error ->
            _events.emit(PeerEvent.SendFailed(error.message ?: "File send failed"))
            return
        }
        if (sent == null) {
            _events.emit(PeerEvent.SendFailed("UDP tunnel is not ready"))
            return
        }
        _state.update { it.copy(sentMessages = it.sentMessages + 1, sentBytes = it.sentBytes + bytes.size) }
    }

    fun close() {
        closeTunnelOnly()
        natResult = null
        importedPeerName = null
        remoteEndpoint = null
        tunnelPassword = null
        incomingFiles.clear()
        _state.update { it.copy(phase = PeerPhase.Idle) }
    }

    private fun handleIncomingText(text: String, messageId: Long) {
        _state.update {
            it.copy(
                receivedMessages = it.receivedMessages + 1,
                receivedBytes = it.receivedBytes + text.toByteArray().size
            )
        }
        _events.tryEmit(PeerEvent.IncomingText(text, messageId))
    }

    private fun handleFileStart(file: FileStart) {
        incomingFiles[file.transferId] = IncomingFileAssembly(file)
        emitReceiveProgress(file, 0L)
    }

    private fun handleFileChunk(chunk: FileChunk) {
        val assembly = incomingFiles[chunk.transferId] ?: return
        assembly.chunks[chunk.index] = chunk.bytes
        emitReceiveProgress(assembly.start, assembly.receivedBytes())
    }

    private fun handleFileEnd(end: FileEnd) {
        val assembly = incomingFiles.remove(end.transferId) ?: return
        val orderedChunks = (0 until end.chunks).mapNotNull { assembly.chunks[it] }
        if (orderedChunks.size != end.chunks) {
            _events.tryEmit(PeerEvent.ConnectionLost("File transfer ended with missing chunks"))
            return
        }
        val bytes = orderedChunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
        val actualSha256 = bytes.sha256()
        if (!assembly.start.sha256.contentEquals(end.sha256) || !actualSha256.contentEquals(end.sha256)) {
            _events.tryEmit(PeerEvent.ConnectionLost("File checksum mismatch"))
            return
        }
        val dir = config.fileSaveDir ?: createTempDir("directlink_files_")
        dir.mkdirs()
        val safeName = assembly.start.name.safeFileName()
        val output = File(dir, safeName).uniqueSibling()
        output.writeBytes(bytes)
        _state.update {
            it.copy(
                receivedMessages = it.receivedMessages + 1,
                receivedBytes = it.receivedBytes + bytes.size
            )
        }
        emitReceiveProgress(assembly.start.copy(name = safeName), bytes.size.toLong())
        _events.tryEmit(PeerEvent.IncomingFile(safeName, bytes.size.toLong(), output.absolutePath, actualSha256))
    }

    private fun handleFileSendProgress(progress: FileTransferProgress) {
        _events.tryEmit(
            PeerEvent.FileTransferProgress(
                direction = FileTransferDirection.Sending,
                name = progress.name,
                completedBytes = progress.completedBytes,
                totalBytes = progress.totalBytes
            )
        )
    }

    private fun emitReceiveProgress(file: FileStart, completedBytes: Long) {
        _events.tryEmit(
            PeerEvent.FileTransferProgress(
                direction = FileTransferDirection.Receiving,
                name = file.name.safeFileName(),
                completedBytes = completedBytes.coerceAtMost(file.sizeBytes),
                totalBytes = file.sizeBytes
            )
        )
    }

    private fun closeTunnelOnly() {
        tunnelSession?.close()
        tunnelSession = null
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

    private data class IncomingFileAssembly(
        val start: FileStart,
        val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap()
    ) {
        fun receivedBytes(): Long = chunks.values.sumOf { it.size.toLong() }
    }
}

private fun File.uniqueSibling(): File {
    if (!exists()) return this
    val base = nameWithoutExtension
    val ext = extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ""
    var index = 1
    while (true) {
        val candidate = File(parentFile, "$base-$index$ext")
        if (!candidate.exists()) return candidate
        index++
    }
}

private fun String.safeFileName(): String {
    val forbidden = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    return map { if (it in forbidden) '_' else it }.joinToString("").ifBlank { "directlink-file" }
}
