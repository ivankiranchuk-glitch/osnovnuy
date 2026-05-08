package com.kirivsoft.directlink.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread

class HolePunchingManagerTest {
    private val manager = HolePunchingManager()

    @Test
    fun `open NAT uses direct strategy`() {
        assertEquals(PunchStrategy.DIRECT, manager.selectStrategy(NatType.OPEN, NatType.UNKNOWN))
        assertEquals(PunchStrategy.DIRECT, manager.selectStrategy(NatType.UNKNOWN, NatType.OPEN))
    }

    @Test
    fun `symmetric pair requires relay`() {
        assertEquals(PunchStrategy.RELAY_REQUIRED, manager.selectStrategy(NatType.SYMMETRIC, NatType.SYMMETRIC))
    }

    @Test
    fun `one symmetric side uses port guessing`() {
        assertEquals(PunchStrategy.PORT_GUESSING, manager.selectStrategy(NatType.SYMMETRIC, NatType.RESTRICTED))
        assertEquals(PunchStrategy.PORT_GUESSING, manager.selectStrategy(NatType.RESTRICTED, NatType.SYMMETRIC))
    }

    @Test
    fun `restricted NAT uses simultaneous strategy`() {
        assertEquals(PunchStrategy.SIMULTANEOUS, manager.selectStrategy(NatType.RESTRICTED, NatType.PORT_RESTRICTED))
    }

    @Test
    fun `local UDP punch can receive response`() {
        DatagramSocket(0).use { responder ->
            DatagramSocket(0).use { sender ->
                val responderThread = thread(start = true) {
                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)
                    responder.receive(packet)
                    responder.send(DatagramPacket(buffer, packet.length, packet.address, packet.port))
                }

                val result = manager.punch(
                    localSocket = sender,
                    localNatType = NatType.OPEN,
                    remote = PeerEndpoint("127.0.0.1", responder.localPort, NatType.OPEN),
                    timeoutMs = 1_500
                )

                assertTrue(result is PunchResult.Success)
                assertEquals(PunchStrategy.DIRECT, (result as PunchResult.Success).strategy)
                responderThread.join(1_000)
            }
        }
    }
}
