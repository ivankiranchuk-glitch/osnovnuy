package com.kirivsoft.directlink.relay

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class TcpRelayClient(
    host: String,
    port: Int,
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 2_000
) : Closeable {
    private val socket = Socket().apply {
        connect(java.net.InetSocketAddress(host, port), connectTimeoutMs)
        soTimeout = readTimeoutMs
    }
    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    fun send(frame: RelayFrame) {
        writer.write(RelayFrameCodec.encode(frame))
        writer.newLine()
        writer.flush()
    }

    fun receive(): RelayFrame? = try {
        reader.readLine()?.let(RelayFrameCodec::decode)
    } catch (_: SocketTimeoutException) {
        null
    }

    override fun close() {
        socket.close()
    }
}

class TcpRelayServer(
    requestedPort: Int = 0,
    private val coordinator: RelayServerCoordinator = RelayServerCoordinator()
) : Closeable {
    private val serverSocket = ServerSocket(requestedPort)
    private val clientsByPeerId = ConcurrentHashMap<String, ClientConnection>()
    private val workers = ConcurrentHashMap.newKeySet<Thread>()
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    val port: Int get() = serverSocket.localPort

    fun start() {
        if (running) return
        running = true
        acceptThread = thread(name = "directlink-relay-accept", isDaemon = true) {
            while (running && !serverSocket.isClosed) {
                runCatching {
                    val socket = serverSocket.accept()
                    val client = ClientConnection(socket)
                    val worker = thread(name = "directlink-relay-client", isDaemon = true) {
                        handleClient(client)
                    }
                    workers += worker
                }
            }
        }
    }

    private fun handleClient(client: ClientConnection) {
        client.use {
            while (running && !client.socket.isClosed) {
                val line = client.reader.readLine() ?: break
                val frame = RelayFrameCodec.decode(line) ?: RelayFrame.Error("Malformed relay frame")
                handleFrame(client, frame)
            }
        }
        client.peerId?.let(clientsByPeerId::remove)
    }

    private fun handleFrame(client: ClientConnection, frame: RelayFrame) {
        when (frame) {
            is RelayFrame.Register -> {
                client.peerId = frame.peerId
                clientsByPeerId[frame.peerId] = client
                coordinator.handle(frame).forEach(client::send)
            }
            is RelayFrame.Join -> {
                val responses = coordinator.handle(frame)
                if (responses.any { it is RelayFrame.Joined }) {
                    client.peerId = frame.peerId
                    clientsByPeerId[frame.peerId] = client
                }
                responses.forEach(client::send)
            }
            is RelayFrame.Payload -> {
                val responses = coordinator.handle(frame)
                responses.forEach { response ->
                    if (response is RelayFrame.Payload) {
                        clientsByPeerId[response.toPeerId]?.send(response)
                            ?: client.send(RelayFrame.Error("Relay target peer is not connected"))
                    } else {
                        client.send(response)
                    }
                }
            }
            else -> coordinator.handle(frame).forEach(client::send)
        }
    }

    override fun close() {
        running = false
        clientsByPeerId.values.forEach { runCatching { it.close() } }
        clientsByPeerId.clear()
        runCatching { serverSocket.close() }
        acceptThread?.join(500)
        workers.forEach { it.join(500) }
        workers.clear()
    }

    private class ClientConnection(
        val socket: Socket
    ) : Closeable {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
        @Volatile var peerId: String? = null

        @Synchronized
        fun send(frame: RelayFrame) {
            writer.write(RelayFrameCodec.encode(frame))
            writer.newLine()
            writer.flush()
        }

        override fun close() {
            socket.close()
        }
    }
}
