package com.kirivsoft.directlink.desktop

import com.kirivsoft.directlink.relay.TcpRelayServer
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_RELAY_PORT
    val server = TcpRelayServer(requestedPort = port)
    val stopLatch = CountDownLatch(1)

    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.close()
            stopLatch.countDown()
        }
    )

    server.start()
    println("DirectLink relay server listening on 0.0.0.0:${server.port}")
    println("Press Ctrl+C to stop.")
    stopLatch.await()
}

private const val DEFAULT_RELAY_PORT = 47777
