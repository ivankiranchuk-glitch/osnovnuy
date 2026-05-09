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
import java.util.concurrent.atomic.AtomicLong

class UdpTunnelSession(
    private val socket: DatagramSocket,
    private val remoteAddress: InetSocketAddress,
    private val scope: CoroutineScope,
    private val onText: (String, Long) -> Unit,
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
        val payload = TunnelFrameCodec.encodeText(messageId, text)
        socket.send(DatagramPacket(payload, payload.size, remoteAddress.address, remoteAddress.port))
        return messageId
    }

    fun close() {
        receiveJob?.cancel()
        receiveJob = null
        socket.close()
    }

    companion object {
        private const val RECEIVE_TIMEOUT_MS = 500
        private const val MAX_PACKET_SIZE = 64 * 1024
    }
}

sealed class TunnelFrame {
    data class Text(val messageId: Long, val text: String) : TunnelFrame()
}

object TunnelFrameCodec {
    private val MAGIC = byteArrayOf('D'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())
    private const val TYPE_TEXT: Byte = 1
    private const val HEADER_SIZE = 4 + 1 + 8 + 4

    fun encodeText(messageId: Long, text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        require(payload.size <= 60 * 1024) { "Text message is too large for one UDP frame" }
        return ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(TYPE_TEXT)
            .putLong(messageId)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun decode(bytes: ByteArray): TunnelFrame? {
        if (bytes.size < HEADER_SIZE) return null
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
            else -> null
        }
    }
}
