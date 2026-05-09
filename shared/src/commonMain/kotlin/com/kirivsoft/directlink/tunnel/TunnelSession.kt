package com.kirivsoft.directlink.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class UdpTunnelSession(
    private val socket: DatagramSocket,
    private val remoteAddress: InetSocketAddress,
    private val scope: CoroutineScope,
    private val onText: (String, Long) -> Unit,
    private val onFileStart: (FileStart) -> Unit = {},
    private val onFileChunk: (FileChunk) -> Unit = {},
    private val onFileEnd: (FileEnd) -> Unit = {},
    private val onClosed: (String) -> Unit
) {
    private val nextMessageId = AtomicLong(1)
    private var receiveJob: Job? = null

    fun start() {
        if (receiveJob != null) return
        receiveJob = scope.launch(Dispatchers.IO) {
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            val buffer = ByteArray(MAX_PACKET_SIZE)
            while (isActive && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val bytes = buffer.copyOf(packet.length)
                    when (val frame = TunnelFrameCodec.decode(bytes)) {
                        is TunnelFrame.Text -> onText(frame.text, frame.messageId)
                        is TunnelFrame.FileStartFrame -> onFileStart(frame.file)
                        is TunnelFrame.FileChunkFrame -> onFileChunk(frame.chunk)
                        is TunnelFrame.FileEndFrame -> onFileEnd(frame.end)
                        null -> Unit
                    }
                } catch (_: SocketTimeoutException) {
                    // Keep polling so coroutine cancellation and socket close are noticed.
                } catch (_: SocketException) {
                    if (!socket.isClosed) onClosed("UDP tunnel socket closed unexpectedly")
                    break
                } catch (error: Exception) {
                    onClosed(error.message ?: "UDP tunnel receive failed")
                    break
                }
            }
        }
    }

    fun sendText(text: String): Long {
        val messageId = nextMessageId.getAndIncrement()
        sendFrame(TunnelFrameCodec.encodeText(messageId, text))
        return messageId
    }

    fun sendFile(name: String, bytes: ByteArray, chunkSize: Int = DEFAULT_FILE_CHUNK_SIZE): Long {
        require(chunkSize in 1..MAX_FILE_CHUNK_SIZE) { "Invalid file chunk size" }
        val transferId = nextMessageId.getAndIncrement()
        val sha256 = bytes.sha256()
        sendFrame(TunnelFrameCodec.encodeFileStart(transferId, name, bytes.size.toLong(), sha256))
        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            sendFrame(TunnelFrameCodec.encodeFileChunk(transferId, index, bytes.copyOfRange(offset, end)))
            offset = end
            index++
        }
        sendFrame(TunnelFrameCodec.encodeFileEnd(transferId, index, sha256))
        return transferId
    }

    fun close() {
        receiveJob?.cancel()
        receiveJob = null
        socket.close()
    }

    private fun sendFrame(payload: ByteArray) {
        socket.send(DatagramPacket(payload, payload.size, remoteAddress.address, remoteAddress.port))
    }

    companion object {
        const val DEFAULT_FILE_CHUNK_SIZE = 16 * 1024
        const val MAX_FILE_CHUNK_SIZE = 60 * 1024
        private const val RECEIVE_TIMEOUT_MS = 500
        private const val MAX_PACKET_SIZE = 64 * 1024
    }
}

sealed class TunnelFrame {
    data class Text(val messageId: Long, val text: String) : TunnelFrame()
    data class FileStartFrame(val file: FileStart) : TunnelFrame()
    data class FileChunkFrame(val chunk: FileChunk) : TunnelFrame()
    data class FileEndFrame(val end: FileEnd) : TunnelFrame()
}

data class FileStart(
    val transferId: Long,
    val name: String,
    val sizeBytes: Long,
    val sha256: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is FileStart &&
        transferId == other.transferId &&
        name == other.name &&
        sizeBytes == other.sizeBytes &&
        sha256.contentEquals(other.sha256)

    override fun hashCode(): Int =
        31 * (31 * (31 * transferId.hashCode() + name.hashCode()) + sizeBytes.hashCode()) + sha256.contentHashCode()
}

data class FileChunk(
    val transferId: Long,
    val index: Int,
    val bytes: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is FileChunk &&
        transferId == other.transferId &&
        index == other.index &&
        bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * transferId.hashCode() + index) + bytes.contentHashCode()
}

data class FileEnd(
    val transferId: Long,
    val chunks: Int,
    val sha256: ByteArray
) {
    override fun equals(other: Any?): Boolean = other is FileEnd &&
        transferId == other.transferId &&
        chunks == other.chunks &&
        sha256.contentEquals(other.sha256)

    override fun hashCode(): Int = 31 * (31 * transferId.hashCode() + chunks) + sha256.contentHashCode()
}

object TunnelFrameCodec {
    private val MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
    private const val TYPE_TEXT: Byte = 1
    private const val TYPE_FILE_START: Byte = 2
    private const val TYPE_FILE_CHUNK: Byte = 3
    private const val TYPE_FILE_END: Byte = 4
    private const val BASE_HEADER_SIZE = 4 + 1 + 8 + 4
    private const val SHA256_SIZE = 32

    fun encodeText(messageId: Long, text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size <= 60 * 1024) { "Text message is too large for one UDP frame" }
        return encodePayload(TYPE_TEXT, messageId, payload)
    }

    fun encodeFileStart(transferId: Long, name: String, sizeBytes: Long, sha256: ByteArray): ByteArray {
        require(sha256.size == SHA256_SIZE) { "SHA-256 must be 32 bytes" }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val payload = ByteBuffer.allocate(8 + SHA256_SIZE + 2 + nameBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(sizeBytes)
            .put(sha256)
            .putShort(nameBytes.size.toShort())
            .put(nameBytes)
            .array()
        return encodePayload(TYPE_FILE_START, transferId, payload)
    }

    fun encodeFileChunk(transferId: Long, index: Int, bytes: ByteArray): ByteArray {
        require(bytes.size <= UdpTunnelSession.MAX_FILE_CHUNK_SIZE) { "File chunk is too large for one UDP frame" }
        val payload = ByteBuffer.allocate(4 + bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(index)
            .put(bytes)
            .array()
        return encodePayload(TYPE_FILE_CHUNK, transferId, payload)
    }

    fun encodeFileEnd(transferId: Long, chunks: Int, sha256: ByteArray): ByteArray {
        require(sha256.size == SHA256_SIZE) { "SHA-256 must be 32 bytes" }
        val payload = ByteBuffer.allocate(4 + SHA256_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(chunks)
            .put(sha256)
            .array()
        return encodePayload(TYPE_FILE_END, transferId, payload)
    }

    fun decode(bytes: ByteArray): TunnelFrame? {
        if (bytes.size < BASE_HEADER_SIZE) return null
        if (!bytes.copyOf(MAGIC.size).contentEquals(MAGIC)) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(MAGIC.size)
        val type = buffer.get()
        val messageId = buffer.long
        val payloadSize = buffer.int
        if (payloadSize < 0 || payloadSize > buffer.remaining()) return null
        val payload = ByteArray(payloadSize).also(buffer::get)
        return when (type) {
            TYPE_TEXT -> TunnelFrame.Text(messageId, payload.toString(Charsets.UTF_8))
            TYPE_FILE_START -> decodeFileStart(messageId, payload)
            TYPE_FILE_CHUNK -> decodeFileChunk(messageId, payload)
            TYPE_FILE_END -> decodeFileEnd(messageId, payload)
            else -> null
        }
    }

    private fun encodePayload(type: Byte, messageId: Long, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(BASE_HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(type)
            .putLong(messageId)
            .putInt(payload.size)
            .put(payload)
            .array()

    private fun decodeFileStart(transferId: Long, payload: ByteArray): TunnelFrame.FileStartFrame? {
        if (payload.size < 8 + SHA256_SIZE + 2) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val sizeBytes = buffer.long
        val sha256 = ByteArray(SHA256_SIZE).also(buffer::get)
        val nameSize = buffer.short.toInt() and 0xffff
        if (nameSize > buffer.remaining()) return null
        val nameBytes = ByteArray(nameSize).also(buffer::get)
        return TunnelFrame.FileStartFrame(FileStart(transferId, nameBytes.toString(Charsets.UTF_8), sizeBytes, sha256))
    }

    private fun decodeFileChunk(transferId: Long, payload: ByteArray): TunnelFrame.FileChunkFrame? {
        if (payload.size < 4) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val index = buffer.int
        val chunk = ByteArray(buffer.remaining()).also(buffer::get)
        return TunnelFrame.FileChunkFrame(FileChunk(transferId, index, chunk))
    }

    private fun decodeFileEnd(transferId: Long, payload: ByteArray): TunnelFrame.FileEndFrame? {
        if (payload.size != 4 + SHA256_SIZE) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val chunks = buffer.int
        val sha256 = ByteArray(SHA256_SIZE).also(buffer::get)
        return TunnelFrame.FileEndFrame(FileEnd(transferId, chunks, sha256))
    }
}

fun ByteArray.sha256(): ByteArray = MessageDigest.getInstance("SHA-256").digest(this)
