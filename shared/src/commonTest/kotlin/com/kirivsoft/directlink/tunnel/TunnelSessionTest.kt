package com.kirivsoft.directlink.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TunnelSessionTest {
    @Test
    fun `text frame round trips through codec`() {
        val encoded = TunnelFrameCodec.encodeText(42, "hello")
        val decoded = TunnelFrameCodec.decode(encoded)

        assertTrue(decoded is TunnelFrame.Text)
        decoded as TunnelFrame.Text
        assertEquals(42, decoded.messageId)
        assertEquals("hello", decoded.text)
    }

    @Test
    fun `file frames round trip through codec`() {
        val bytes = "file-body".toByteArray()
        val sha256 = bytes.sha256()

        val start = TunnelFrameCodec.decode(TunnelFrameCodec.encodeFileStart(7, "note.txt", bytes.size.toLong(), sha256))
        val chunk = TunnelFrameCodec.decode(TunnelFrameCodec.encodeFileChunk(7, 0, bytes))
        val end = TunnelFrameCodec.decode(TunnelFrameCodec.encodeFileEnd(7, 1, sha256))

        assertTrue(start is TunnelFrame.FileStartFrame)
        assertTrue(chunk is TunnelFrame.FileChunkFrame)
        assertTrue(end is TunnelFrame.FileEndFrame)
        start as TunnelFrame.FileStartFrame
        chunk as TunnelFrame.FileChunkFrame
        end as TunnelFrame.FileEndFrame
        assertEquals(FileStart(7, "note.txt", bytes.size.toLong(), sha256), start.file)
        assertEquals(FileChunk(7, 0, bytes), chunk.chunk)
        assertEquals(FileEnd(7, 1, sha256), end.end)
    }

    @Test
    fun `codec ignores malformed frame`() {
        assertNull(TunnelFrameCodec.decode(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `UDP tunnel session sends and receives text`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val received = LinkedBlockingQueue<String>()

        DatagramSocket(0).use { socketA ->
            DatagramSocket(0).use { socketB ->
                val sessionA = UdpTunnelSession(
                    socket = socketA,
                    remoteAddress = InetSocketAddress("127.0.0.1", socketB.localPort),
                    scope = scope,
                    onText = { text, _ -> received.offer("A:$text") },
                    onClosed = {}
                )
                val sessionB = UdpTunnelSession(
                    socket = socketB,
                    remoteAddress = InetSocketAddress("127.0.0.1", socketA.localPort),
                    scope = scope,
                    onText = { text, _ -> received.offer("B:$text") },
                    onClosed = {}
                )

                sessionA.start()
                sessionB.start()
                sessionA.sendText("ping")
                sessionB.sendText("pong")

                val messages = setOf(
                    received.poll(2, TimeUnit.SECONDS),
                    received.poll(2, TimeUnit.SECONDS)
                )
                assertEquals(setOf("B:ping", "A:pong"), messages)
                sessionA.close()
                sessionB.close()
            }
        }
        scope.cancel()
    }

    @Test
    fun `UDP tunnel session sends file frames`() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val starts = LinkedBlockingQueue<FileStart>()
        val chunks = LinkedBlockingQueue<FileChunk>()
        val ends = LinkedBlockingQueue<FileEnd>()
        val bytes = ByteArray(40_000) { (it % 251).toByte() }

        DatagramSocket(0).use { senderSocket ->
            DatagramSocket(0).use { receiverSocket ->
                val sender = UdpTunnelSession(
                    socket = senderSocket,
                    remoteAddress = InetSocketAddress("127.0.0.1", receiverSocket.localPort),
                    scope = scope,
                    onText = { _, _ -> },
                    onClosed = {}
                )
                val receiver = UdpTunnelSession(
                    socket = receiverSocket,
                    remoteAddress = InetSocketAddress("127.0.0.1", senderSocket.localPort),
                    scope = scope,
                    onText = { _, _ -> },
                    onFileStart = starts::offer,
                    onFileChunk = chunks::offer,
                    onFileEnd = ends::offer,
                    onClosed = {}
                )

                sender.start()
                receiver.start()
                sender.sendFile("payload.bin", bytes, chunkSize = 16_000)

                val start = starts.poll(2, TimeUnit.SECONDS)
                val receivedChunks = listOf(
                    chunks.poll(2, TimeUnit.SECONDS),
                    chunks.poll(2, TimeUnit.SECONDS),
                    chunks.poll(2, TimeUnit.SECONDS)
                )
                val end = ends.poll(2, TimeUnit.SECONDS)

                assertEquals("payload.bin", start.name)
                assertEquals(bytes.size.toLong(), start.sizeBytes)
                assertEquals(listOf(0, 1, 2), receivedChunks.map { it.index })
                assertArrayEquals(bytes.copyOfRange(0, 16_000), receivedChunks[0].bytes)
                assertArrayEquals(bytes.copyOfRange(16_000, 32_000), receivedChunks[1].bytes)
                assertArrayEquals(bytes.copyOfRange(32_000, bytes.size), receivedChunks[2].bytes)
                assertEquals(3, end.chunks)
                assertArrayEquals(bytes.sha256(), end.sha256)
                sender.close()
                receiver.close()
            }
        }
        scope.cancel()
    }
}
