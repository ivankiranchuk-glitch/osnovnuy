package com.kirivsoft.directlink

import com.kirivsoft.directlink.network.HolePunchingManager
import com.kirivsoft.directlink.network.NatDetectionResult
import com.kirivsoft.directlink.network.NatDetector
import com.kirivsoft.directlink.network.PeerEndpoint
import com.kirivsoft.directlink.network.PunchResult
import com.kirivsoft.directlink.packet.DlpParseResult
import com.kirivsoft.directlink.packet.DlpSerializer
import com.kirivsoft.directlink.relay.RelayClientHandshake
import com.kirivsoft.directlink.relay.RelayFrame
import com.kirivsoft.directlink.relay.RelayHandshakeRole
import com.kirivsoft.directlink.relay.RelayHandshakeState
import com.kirivsoft.directlink.relay.TcpRelayClient
import com.kirivsoft.directlink.tunnel.FileChunk
import com.kirivsoft.directlink.tunnel.FileEnd
import com.kirivsoft.directlink.tunnel.FileStart
import com.kirivsoft.directlink.tunnel.FileTransferProgress
import com.kirivsoft.directlink.tunnel.TunnelCipher
import com.kirivsoft.directlink.tunnel.TunnelFrame
import com.kirivsoft.directlink.tunnel.TunnelFrameCodec
import com.kirivsoft.directlink.tunnel.UdpTunnelSession
import com.kirivsoft.directlink.tunnel.sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private var importedPeerId: String? = null
    private var remoteEndpoint: PeerEndpoint? = null
    private var tunnelPassword: String? = null
    private var tunnelSession: UdpTunnelSession? = null
    private var relayClient: TcpRelayClient? = null
    private var relayCipher: TunnelCipher? = null
    private var relayReceiveJob: Job? = null
    private val nextRelayMessageId = AtomicLong(1)
    private val incomingFiles = ConcurrentHashMap<Long, IncomingFileAssembly>()
    private val relayChunkAcknowledgements = ConcurrentHashMap<Long, MutableSet<Int>>()
    private val relayOutgoingFiles = ConcurrentHashMap<Long, OutgoingFileTransfer>()
    private val relayCancelledTransfers = ConcurrentHashMap.newKeySet<Long>()
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
                importedPeerId = result.packet.deviceUuid
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
        closeRelayOnly()
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
            moveToRelayOrError(peerName, "Connection failed: ${error.message}")
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
                moveToRelayOrError(peerName, "Relay is required for this NAT pair: ${punch.reason}")
            }
            is PunchResult.Failed -> {
                socket.close()
                moveToRelayOrError(peerName, "Connection failed: ${punch.reason}")
            }
        }
    }

    suspend fun connectViaRelay(
        relayUrl: String? = config.relayUrl,
        role: RelayHandshakeRole = RelayHandshakeRole.Host,
        sessionId: String? = null
    ) = withContext(Dispatchers.IO) {
        val target = relayUrl?.takeIf { it.isNotBlank() } ?: run {
            _events.emit(PeerEvent.SendFailed("Relay URL is not configured"))
            return@withContext
        }
        val token = tunnelPassword?.takeIf { it.isNotBlank() } ?: run {
            _events.emit(PeerEvent.SendFailed("Packet password is required before relay connect"))
            return@withContext
        }
        val peerName = importedPeerName ?: "DirectLink peer"
        val address = runCatching { RelayAddress.parse(target) }.getOrElse { error ->
            _events.emit(PeerEvent.SendFailed(error.message ?: "Invalid relay URL"))
            return@withContext
        }

        closeTunnelOnly()
        closeRelayOnly()
        val client = runCatching { TcpRelayClient(address.host, address.port) }.getOrElse { error ->
            _events.emit(PeerEvent.SendFailed(error.message ?: "Relay connection failed"))
            return@withContext
        }
        val handshake = runCatching {
            RelayClientHandshake(
                peerId = config.deviceUuid,
                token = token,
                role = role,
                requestedSessionId = sessionId
            )
        }.getOrElse { error ->
            client.close()
            _events.emit(PeerEvent.SendFailed(error.message ?: "Relay handshake setup failed"))
            return@withContext
        }

        client.send(handshake.start())
        val response = client.receive() ?: run {
            client.close()
            _events.emit(PeerEvent.SendFailed("Relay handshake timed out"))
            return@withContext
        }
        handshake.handle(response)
        if (handshake.state != RelayHandshakeState.Ready || handshake.sessionId == null) {
            client.close()
            _events.emit(PeerEvent.SendFailed(handshake.failureReason ?: "Relay handshake failed"))
            return@withContext
        }

        relayClient = client
        relayCipher = TunnelCipher.fromPassword(token)
        startRelayReceive(client)
        _state.update {
            it.copy(
                phase = PeerPhase.RelayConnected(
                    peerName = peerName,
                    relayUrl = target,
                    relaySessionId = handshake.sessionId!!
                )
            )
        }
    }

    suspend fun sendText(text: String) {
        if (!state.value.isConnected) {
            _events.emit(PeerEvent.SendFailed("Session is not connected"))
            return
        }
        val phase = state.value.phase
        if (phase is PeerPhase.RelayConnected) {
            sendRelayText(phase, text)
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
        val phase = state.value.phase
        if (phase is PeerPhase.RelayConnected) {
            sendRelayFile(phase, file)
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

    fun cancelFileTransfers() {
        val udpCancelled = tunnelSession?.cancelFileTransfers() ?: false
        val relayActive = relayOutgoingFiles.keys.toList()
        relayCancelledTransfers.addAll(relayActive)
        val cancelled = udpCancelled || relayActive.isNotEmpty()
        if (!cancelled) {
            _events.tryEmit(PeerEvent.SendFailed("No active file transfer"))
        }
    }

    fun close() {
        closeTunnelOnly()
        closeRelayOnly()
        natResult = null
        importedPeerName = null
        importedPeerId = null
        remoteEndpoint = null
        tunnelPassword = null
        incomingFiles.clear()
        _state.update { it.copy(phase = PeerPhase.Idle) }
    }

    private suspend fun sendRelayText(phase: PeerPhase.RelayConnected, text: String) {
        val targetPeerId = importedPeerId ?: run {
            _events.emit(PeerEvent.SendFailed("Remote peer id is missing"))
            return
        }
        val messageId = nextRelayMessageId.getAndIncrement()
        val frameBytes = TunnelFrameCodec.encodeText(messageId, text)
        runCatching { sendRelayTunnelFrame(phase.relaySessionId, targetPeerId, frameBytes) }.getOrElse { error ->
            _events.emit(PeerEvent.SendFailed(error.message ?: "Relay text send failed"))
            return
        }
        _state.update { it.copy(sentMessages = it.sentMessages + 1, sentBytes = it.sentBytes + text.toByteArray().size) }
    }

    private suspend fun sendRelayFile(phase: PeerPhase.RelayConnected, file: File) {
        val targetPeerId = importedPeerId ?: run {
            _events.emit(PeerEvent.SendFailed("Remote peer id is missing"))
            return
        }
        val bytes = runCatching { file.readBytes() }.getOrElse { error ->
            _events.emit(PeerEvent.SendFailed(error.message ?: "Cannot read file"))
            return
        }
        val transferId = nextRelayMessageId.getAndIncrement()
        val sha256 = bytes.sha256()
        val chunks = bytes.toChunks(UdpTunnelSession.DEFAULT_FILE_CHUNK_SIZE)
        val acked = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
        relayChunkAcknowledgements[transferId] = acked
        relayOutgoingFiles[transferId] = OutgoingFileTransfer(file.name, bytes.size.toLong(), chunks.map { it.size.toLong() })
        reportRelaySendProgress(transferId)

        runCatching {
            ensureRelayNotCancelled(transferId)
            sendRelayTunnelFrame(
                phase.relaySessionId,
                targetPeerId,
                TunnelFrameCodec.encodeFileStart(transferId, file.name, bytes.size.toLong(), sha256)
            )
            chunks.forEachIndexed { index, chunk ->
                ensureRelayNotCancelled(transferId)
                sendRelayTunnelFrame(
                    phase.relaySessionId,
                    targetPeerId,
                    TunnelFrameCodec.encodeFileChunk(transferId, index, chunk)
                )
            }
            waitForRelayMissingAcks(phase.relaySessionId, targetPeerId, acked, chunks.size, chunks, transferId)
            ensureRelayNotCancelled(transferId)
            sendRelayTunnelFrame(
                phase.relaySessionId,
                targetPeerId,
                TunnelFrameCodec.encodeFileEnd(transferId, chunks.size, sha256)
            )
            reportRelaySendProgress(transferId)
        }.getOrElse { error ->
            relayChunkAcknowledgements.remove(transferId)
            relayOutgoingFiles.remove(transferId)
            relayCancelledTransfers.remove(transferId)
            _events.emit(PeerEvent.SendFailed(error.message ?: "Relay file send failed"))
            return
        }

        relayChunkAcknowledgements.remove(transferId)
        relayOutgoingFiles.remove(transferId)
        relayCancelledTransfers.remove(transferId)
        _state.update { it.copy(sentMessages = it.sentMessages + 1, sentBytes = it.sentBytes + bytes.size) }
    }

    private fun sendRelayTunnelFrame(sessionId: String, targetPeerId: String, frameBytes: ByteArray) {
        val client = relayClient ?: error("Relay client is not ready")
        val cipher = relayCipher ?: error("Relay cipher is not ready")
        client.send(
            RelayFrame.Payload(
                sessionId = sessionId,
                fromPeerId = config.deviceUuid,
                toPeerId = targetPeerId,
                bytes = cipher.encrypt(frameBytes)
            )
        )
    }

    private fun waitForRelayMissingAcks(
        sessionId: String,
        targetPeerId: String,
        acked: Set<Int>,
        expectedChunks: Int,
        chunks: List<ByteArray>,
        transferId: Long
    ) {
        var retries = 0
        while (acked.size < expectedChunks) {
            ensureRelayNotCancelled(transferId)
            waitForRelayAcks(acked, expectedChunks, transferId)
            ensureRelayNotCancelled(transferId)
            if (acked.size >= expectedChunks) return
            if (retries >= RELAY_FILE_CHUNK_RETRIES) {
                throw IllegalStateException("Relay file transfer acknowledgement timeout")
            }
            chunks.forEachIndexed { index, chunk ->
                ensureRelayNotCancelled(transferId)
                if (index !in acked) {
                    sendRelayTunnelFrame(sessionId, targetPeerId, TunnelFrameCodec.encodeFileChunk(transferId, index, chunk))
                }
            }
            retries++
        }
    }

    private fun waitForRelayAcks(acked: Set<Int>, expectedChunks: Int, transferId: Long) {
        val deadline = System.currentTimeMillis() + RELAY_FILE_ACK_TIMEOUT_MS
        while (acked.size < expectedChunks && System.currentTimeMillis() < deadline) {
            ensureRelayNotCancelled(transferId)
            Thread.sleep(RELAY_FILE_ACK_POLL_MS)
        }
    }

    private fun reportRelaySendProgress(transferId: Long) {
        val transfer = relayOutgoingFiles[transferId] ?: return
        val acked = relayChunkAcknowledgements[transferId].orEmpty()
        val completedBytes = acked.sumOf { index -> transfer.chunkSizes.getOrNull(index) ?: 0L }
        handleFileSendProgress(
            FileTransferProgress(
                transferId = transferId,
                name = transfer.name,
                completedBytes = completedBytes.coerceAtMost(transfer.sizeBytes),
                totalBytes = transfer.sizeBytes
            )
        )
    }

    private fun ensureRelayNotCancelled(transferId: Long) {
        if (transferId in relayCancelledTransfers) {
            throw IllegalStateException("File transfer cancelled")
        }
    }

    private fun startRelayReceive(client: TcpRelayClient) {
        relayReceiveJob?.cancel()
        relayReceiveJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val frame = runCatching { client.receive() }.getOrElse { error ->
                    if (!isActive) return@launch
                    _state.update { it.copy(phase = PeerPhase.Error(error.message ?: "Relay receive failed")) }
                    _events.tryEmit(PeerEvent.ConnectionLost(error.message ?: "Relay receive failed"))
                    return@launch
                } ?: continue
                when (frame) {
                    is RelayFrame.Payload -> handleRelayPayload(frame)
                    is RelayFrame.Close -> {
                        _state.update { it.copy(phase = PeerPhase.Error(frame.reason)) }
                        _events.tryEmit(PeerEvent.ConnectionLost(frame.reason))
                        return@launch
                    }
                    is RelayFrame.Error -> _events.tryEmit(PeerEvent.SendFailed(frame.reason))
                    else -> Unit
                }
            }
        }
    }

    private fun handleRelayPayload(frame: RelayFrame.Payload) {
        if (frame.toPeerId != config.deviceUuid) return
        val clearBytes = relayCipher?.decrypt(frame.bytes) ?: return
        when (val tunnelFrame = TunnelFrameCodec.decode(clearBytes)) {
            is TunnelFrame.Text -> handleIncomingText(tunnelFrame.text, tunnelFrame.messageId)
            is TunnelFrame.FileStartFrame -> handleFileStart(tunnelFrame.file)
            is TunnelFrame.FileChunkFrame -> {
                handleFileChunk(tunnelFrame.chunk)
                runCatching {
                    sendRelayTunnelFrame(
                        sessionId = frame.sessionId,
                        targetPeerId = frame.fromPeerId,
                        frameBytes = TunnelFrameCodec.encodeFileAck(tunnelFrame.chunk.transferId, tunnelFrame.chunk.index)
                    )
                }.onFailure { error ->
                    _events.tryEmit(PeerEvent.SendFailed(error.message ?: "Relay file acknowledgement failed"))
                }
            }
            is TunnelFrame.FileEndFrame -> handleFileEnd(tunnelFrame.end)
            is TunnelFrame.FileAckFrame -> {
                relayChunkAcknowledgements[tunnelFrame.ack.transferId]?.add(tunnelFrame.ack.index)
                reportRelaySendProgress(tunnelFrame.ack.transferId)
            }
            null -> Unit
        }
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

    private fun moveToRelayOrError(peerName: String, reason: String) {
        val relayUrl = config.relayUrl?.takeIf { it.isNotBlank() }
        if (relayUrl == null) {
            _state.update { it.copy(phase = PeerPhase.Error(reason)) }
        } else {
            _state.update { it.copy(phase = PeerPhase.RelayRequired(peerName, relayUrl, reason)) }
        }
    }

    private fun closeTunnelOnly() {
        tunnelSession?.close()
        tunnelSession = null
    }

    private fun closeRelayOnly() {
        relayReceiveJob?.cancel()
        relayReceiveJob = null
        relayClient?.close()
        relayClient = null
        relayCipher = null
        relayChunkAcknowledgements.clear()
        relayOutgoingFiles.clear()
        relayCancelledTransfers.clear()
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

    private data class OutgoingFileTransfer(
        val name: String,
        val sizeBytes: Long,
        val chunkSizes: List<Long>
    )

    private data class RelayAddress(val host: String, val port: Int) {
        companion object {
            fun parse(value: String): RelayAddress {
                val normalized = if ("://" in value) value else "tcp://$value"
                val uri = URI(normalized)
                require(uri.scheme == "tcp") { "Relay URL must use tcp://host:port" }
                val host = uri.host ?: error("Relay URL host is missing")
                val port = uri.port.takeIf { it in 1..65535 } ?: error("Relay URL port is missing")
                return RelayAddress(host, port)
            }
        }
    }

    private companion object {
        private const val RELAY_FILE_ACK_TIMEOUT_MS = 1_500L
        private const val RELAY_FILE_ACK_POLL_MS = 20L
        private const val RELAY_FILE_CHUNK_RETRIES = 3
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

private fun ByteArray.toChunks(chunkSize: Int): List<ByteArray> {
    val chunks = mutableListOf<ByteArray>()
    var offset = 0
    while (offset < size) {
        val end = minOf(offset + chunkSize, size)
        chunks += copyOfRange(offset, end)
        offset = end
    }
    return chunks
}
