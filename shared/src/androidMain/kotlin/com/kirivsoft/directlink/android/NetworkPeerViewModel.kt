package com.kirivsoft.directlink.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kirivsoft.directlink.NetworkPeer
import com.kirivsoft.directlink.NetworkPeerState
import com.kirivsoft.directlink.PeerConfig
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class NetworkPeerViewModel(app: Application) : AndroidViewModel(app) {

    val peer = NetworkPeer(
        config = PeerConfig(
            deviceName = loadDeviceName(app),
            deviceUuid = loadOrCreateDeviceUuid(app),
            platform = "Android ${android.os.Build.VERSION.RELEASE}",
            appVersion = getAppVersion(app),
            preferredUdpPort = 0,
            fileSaveDir = File(app.filesDir, "directlink_files")
        ),
        scope = viewModelScope
    )

    val uiState = peer.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NetworkPeerState()
    )

    val uiEvents = peer.events.shareIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        replay = 0
    )

    fun initialize() = viewModelScope.launch {
        peer.initialize()
    }

    fun generatePacket(password: String) = viewModelScope.launch {
        peer.generateDlpPacket(password, getApplication<Application>().cacheDir)
    }

    fun importPacket(file: File, password: String) = viewModelScope.launch {
        peer.importDlpPacket(file, password)
    }

    fun handleIncomingDlpFile(path: String, password: String = "") = viewModelScope.launch {
        if (password.isNotBlank()) {
            peer.importDlpPacket(File(path), password)
        }
    }

    fun connect() = viewModelScope.launch {
        peer.connect()
    }

    fun disconnect() {
        peer.close()
    }

    fun sendText(text: String) = viewModelScope.launch {
        peer.sendText(text)
    }

    fun sendFile(file: File) = viewModelScope.launch {
        peer.sendFile(file)
    }

    fun retry() = viewModelScope.launch {
        peer.close()
        peer.initialize()
    }

    override fun onCleared() {
        peer.close()
        super.onCleared()
    }

    private fun loadDeviceName(ctx: Context): String {
        val model = android.os.Build.MODEL
        return if (model.startsWith(android.os.Build.MANUFACTURER, ignoreCase = true)) {
            model
        } else {
            "${android.os.Build.MANUFACTURER} $model"
        }
    }

    private fun loadOrCreateDeviceUuid(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("directlink_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_uuid", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_uuid", it).apply()
        }
    }

    private fun getAppVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.1.0"
    } catch (_: Exception) {
        "0.1.0"
    }
}
