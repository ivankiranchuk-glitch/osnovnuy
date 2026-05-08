package com.kirivsoft.directlink.desktop

import com.kirivsoft.directlink.NetworkPeer
import com.kirivsoft.directlink.PeerConfig
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class DesktopPeerController(
    deviceName: String = System.getProperty("user.name") ?: "Desktop",
    deviceUuid: String = loadOrCreateUuid(),
    saveDir: File? = File(System.getProperty("user.home"), ".directlink/files")
) {
    private val appScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineName("DirectLinkDesktop")
    )

    val peer = NetworkPeer(
        config = PeerConfig(
            deviceName = deviceName,
            deviceUuid = deviceUuid,
            platform = "Desktop ${System.getProperty("os.name")} / JVM ${System.getProperty("java.version")}",
            appVersion = "0.1.0",
            fileSaveDir = saveDir
        ),
        scope = appScope
    )

    val state = peer.state
    val events = peer.events

    fun initialize() = appScope.launch { peer.initialize() }
    fun connect() = appScope.launch { peer.connect() }
    fun disconnect() = peer.close()

    fun generatePacket(password: String, outputDir: File? = null) =
        appScope.launch { peer.generateDlpPacket(password, outputDir) }

    fun importPacket(file: File, password: String) =
        appScope.launch { peer.importDlpPacket(file, password) }

    fun sendText(text: String) = appScope.launch { peer.sendText(text) }
    fun sendFile(file: File) = appScope.launch { peer.sendFile(file) }

    fun close() {
        peer.close()
        appScope.cancel()
    }

    companion object {
        private fun loadOrCreateUuid(): String {
            val uuidFile = File(System.getProperty("user.home"), ".directlink/device_uuid")
            if (uuidFile.exists()) return uuidFile.readText().trim()

            val uuid = UUID.randomUUID().toString()
            uuidFile.parentFile?.mkdirs()
            uuidFile.writeText(uuid)
            return uuid
        }
    }
}
