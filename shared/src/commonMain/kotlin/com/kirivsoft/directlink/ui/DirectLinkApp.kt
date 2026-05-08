package com.kirivsoft.directlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kirivsoft.directlink.NetworkPeer
import com.kirivsoft.directlink.PeerEvent
import com.kirivsoft.directlink.PeerPhase
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun DirectLinkApp(
    peer: NetworkPeer,
    onShare: (path: String) -> Unit = {},
    onPickFile: (onResult: (String) -> Unit) -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var importPath by remember { mutableStateOf("") }
    val state by peer.state.collectAsState()
    fun packetPassword(): String = password.ifBlank { "directlink" }

    LaunchedEffect(Unit) {
        peer.events.collect { event ->
            when (event) {
                is PeerEvent.DlpPacketReady -> onShare(event.file.absolutePath)
                is PeerEvent.SendFailed -> snackbarHostState.showSnackbar(event.reason)
                is PeerEvent.ConnectionLost -> snackbarHostState.showSnackbar(event.reason)
                else -> Unit
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("DirectLink")
            Text(phaseText(state.phase))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Packet password") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { peer.initialize() } }) {
                    Text("Initialize")
                }
                Button(onClick = { scope.launch { peer.generateDlpPacket(packetPassword()) } }) {
                    Text("Create .dlp")
                }
            }

            OutlinedTextField(
                value = importPath,
                onValueChange = { importPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("DLP file path") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPickFile { importPath = it } }) {
                    Text("Pick file")
                }
                Button(onClick = { scope.launch { peer.importDlpPacket(File(importPath), packetPassword()) } }) {
                    Text("Import")
                }
                Button(onClick = { scope.launch { peer.connect() } }) {
                    Text("Connect")
                }
            }

            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sent messages: ${state.sentMessages}")
                    Text("Received messages: ${state.receivedMessages}")
                    Text("Sent bytes: ${state.sentBytes}")
                    Text("Received bytes: ${state.receivedBytes}")
                }
            }
        }
    }
}

private fun phaseText(phase: PeerPhase): String = when (phase) {
    PeerPhase.Idle -> "Idle"
    PeerPhase.GatheringNetwork -> "Gathering network"
    is PeerPhase.Ready -> "Ready: ${phase.localIp}:${phase.udpPort}"
    is PeerPhase.PacketGenerated -> "Packet generated: ${phase.dlpFile.name}"
    is PeerPhase.AwaitingConnection -> "Awaiting connection with ${phase.peerName}"
    is PeerPhase.Connected -> "Connected to ${phase.peerName}"
    is PeerPhase.Error -> "Error: ${phase.reason}"
}
