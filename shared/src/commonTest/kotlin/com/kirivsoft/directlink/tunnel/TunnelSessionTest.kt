package com.kirivsoft.directlink.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

                assertEquals("B:ping", received.poll(2, TimeUnit.SECONDS))
                assertEquals("A:pong", received.poll(2, TimeUnit.SECONDS))
                sessionA.close()
                sessionB.close()
            }
        }
        scope.cancel()
    }
}
