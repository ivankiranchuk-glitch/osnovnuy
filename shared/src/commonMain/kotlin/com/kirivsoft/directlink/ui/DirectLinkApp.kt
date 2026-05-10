package com.kirivsoft.directlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kirivsoft.directlink.FileTransferDirection
import com.kirivsoft.directlink.NetworkPeer
import com.kirivsoft.directlink.PeerEvent
import com.kirivsoft.directlink.PeerPhase
import com.kirivsoft.directlink.relay.RelayHandshakeRole
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
    val scrollState = rememberScrollState()
    val activity = remember { mutableStateListOf<ActivityItem>() }
    val transfers = remember { mutableStateListOf<TransferItem>() }
    var password by remember { mutableStateOf("") }
    var importPath by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf(defaultRelayUrl()) }
    var relaySessionId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var sendFilePath by remember { mutableStateOf("") }
    val state by peer.state.collectAsState()
    fun packetPassword(): String = password.ifBlank { "directlink" }

    LaunchedEffect(Unit) {
        peer.events.collect { event ->
            when (event) {
                is PeerEvent.DlpPacketReady -> {
                    activity.prepend(ActivityItem("DLP packet", event.file.absolutePath))
                    onShare(event.file.absolutePath)
                }
                is PeerEvent.IncomingText -> activity.prepend(ActivityItem("Incoming text", event.text))
                is PeerEvent.IncomingFile -> activity.prepend(
                    ActivityItem(
                        title = "Incoming file",
                        detail = "${event.name} (${event.sizeBytes} bytes)\n${event.savedPath}"
                    )
                )
                is PeerEvent.FileTransferProgress -> {
                    transfers.upsert(
                        TransferItem(
                            direction = event.direction,
                            name = event.name,
                            completedBytes = event.completedBytes,
                            totalBytes = event.totalBytes,
                            percent = event.percent,
                            complete = event.isComplete
                        )
                    )
                }
                is PeerEvent.SendFailed -> {
                    activity.prepend(ActivityItem("Send failed", event.reason))
                    snackbarHostState.showSnackbar(event.reason)
                }
                is PeerEvent.ConnectionLost -> {
                    activity.prepend(ActivityItem("Connection lost", event.reason))
                    snackbarHostState.showSnackbar(event.reason)
                }
            }
        }
    }

    val relayConnected = state.phase as? PeerPhase.RelayConnected
    LaunchedEffect(relayConnected?.relaySessionId) {
        if (relayConnected != null) {
            relaySessionId = relayConnected.relaySessionId
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("DirectLink", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(phaseText(state.phase), style = MaterialTheme.typography.bodyMedium)

            val relayPhase = state.phase as? PeerPhase.RelayRequired
            if (relayPhase != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Relay required", fontWeight = FontWeight.SemiBold)
                        Text(relayPhase.reason, style = MaterialTheme.typography.bodySmall)
                        Text("Relay: ${relayPhase.relayUrl ?: "not configured"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (relayConnected != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Relay connected", fontWeight = FontWeight.SemiBold)
                        Text("Relay: ${relayConnected.relayUrl}", style = MaterialTheme.typography.bodySmall)
                        Text("Session: ${relayConnected.relaySessionId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

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
                    Text("Pick DLP")
                }
                Button(onClick = { scope.launch { peer.importDlpPacket(File(importPath), packetPassword()) } }) {
                    Text("Import")
                }
                Button(onClick = { scope.launch { peer.connect() } }) {
                    Text("Connect")
                }
            }

            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Relay URL") }
            )
            OutlinedTextField(
                value = relaySessionId,
                onValueChange = { relaySessionId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Relay session") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { peer.connectViaRelay(relayUrl, RelayHandshakeRole.Host) } }) {
                    Text("Host relay")
                }
                Button(
                    onClick = {
                        scope.launch {
                            peer.connectViaRelay(relayUrl, RelayHandshakeRole.Guest, relaySessionId.ifBlank { null })
                        }
                    }
                ) {
                    Text("Join relay")
                }
            }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Message") }
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val text = message
                            message = ""
                            scope.launch { peer.sendText(text) }
                        },
                        enabled = message.isNotBlank()
                    ) {
                        Text("Send text")
                    }
                    Button(onClick = { onPickFile { sendFilePath = it } }) {
                        Text("Pick file")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { peer.sendFile(File(sendFilePath)) } },
                        enabled = sendFilePath.isNotBlank()
                    ) {
                        Text("Send file")
                    }
                    Button(onClick = { peer.cancelFileTransfers() }) {
                        Text("Cancel transfers")
                    }
                }
            }

            if (sendFilePath.isNotBlank()) {
                Text(sendFilePath, style = MaterialTheme.typography.bodySmall)
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

            if (transfers.isNotEmpty()) {
                Text("Transfers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    transfers.take(4).forEach { transfer ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${transfer.direction.label()}: ${transfer.name}", fontWeight = FontWeight.SemiBold)
                                LinearProgressIndicator(
                                    progress = { transfer.percent / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${transfer.percent}% - ${transfer.completedBytes}/${transfer.totalBytes} bytes${if (transfer.complete) " - complete" else ""}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            Text("Activity", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                activity.forEach { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, fontWeight = FontWeight.SemiBold)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private data class ActivityItem(
    val title: String,
    val detail: String
)

private data class TransferItem(
    val direction: FileTransferDirection,
    val name: String,
    val completedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val complete: Boolean
)

private fun MutableList<ActivityItem>.prepend(item: ActivityItem) {
    add(0, item)
    if (size > 30) removeAt(lastIndex)
}

private fun MutableList<TransferItem>.upsert(item: TransferItem) {
    val index = indexOfFirst { it.direction == item.direction && it.name == item.name }
    if (index >= 0) {
        this[index] = item
    } else {
        add(0, item)
    }
    while (size > 8) removeAt(lastIndex)
}

private fun FileTransferDirection.label(): String = when (this) {
    FileTransferDirection.Sending -> "Sending"
    FileTransferDirection.Receiving -> "Receiving"
}

private fun defaultRelayUrl(): String {
    val runtimeName = System.getProperty("java.runtime.name").orEmpty()
    val vmName = System.getProperty("java.vm.name").orEmpty()
    return if (runtimeName.contains("Android", ignoreCase = true) || vmName.contains("Dalvik", ignoreCase = true)) {
        "tcp://10.0.2.2:47777"
    } else {
        "tcp://127.0.0.1:47777"
    }
}

private fun phaseText(phase: PeerPhase): String = when (phase) {
    PeerPhase.Idle -> "Idle"
    PeerPhase.GatheringNetwork -> "Gathering network"
    is PeerPhase.Ready -> "Ready: ${phase.localIp}:${phase.udpPort}"
    is PeerPhase.PacketGenerated -> "Packet generated: ${phase.dlpFile.name}"
    is PeerPhase.AwaitingConnection -> "Awaiting connection with ${phase.peerName}"
    is PeerPhase.Connected -> "Connected to ${phase.peerName}"
    is PeerPhase.RelayRequired -> "Relay required for ${phase.peerName}"
    is PeerPhase.RelayConnected -> "Relay connected to ${phase.peerName}"
    is PeerPhase.Error -> "Error: ${phase.reason}"
}
