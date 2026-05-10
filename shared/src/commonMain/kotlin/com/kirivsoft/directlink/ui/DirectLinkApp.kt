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
    var language by remember { mutableStateOf(UiLanguage.Ukrainian) }
    val text = uiText(language)
    var password by remember { mutableStateOf("") }
    var importPath by remember { mutableStateOf("") }
    var relayUrl by remember { mutableStateOf(defaultRelayUrl()) }
    var relaySessionId by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var sendFilePath by remember { mutableStateOf("") }
    val state by peer.state.collectAsState()
    fun packetPassword(): String = password.ifBlank { "directlink" }

    LaunchedEffect(language) {
        peer.events.collect { event ->
            when (event) {
                is PeerEvent.DlpPacketReady -> {
                    activity.prepend(ActivityItem(text.activityDlpPacket, event.file.absolutePath))
                    onShare(event.file.absolutePath)
                }
                is PeerEvent.IncomingText -> activity.prepend(ActivityItem(text.activityIncomingText, event.text))
                is PeerEvent.IncomingFile -> activity.prepend(
                    ActivityItem(
                        title = text.activityIncomingFile,
                        detail = "${event.name} (${event.sizeBytes} ${text.bytes})\n${event.savedPath}"
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
                    activity.prepend(ActivityItem(text.activitySendFailed, event.reason))
                    snackbarHostState.showSnackbar(event.reason)
                }
                is PeerEvent.ConnectionLost -> {
                    activity.prepend(ActivityItem(text.activityConnectionLost, event.reason))
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
            Text(phaseText(state.phase, text), style = MaterialTheme.typography.bodyMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text.settings, fontWeight = FontWeight.SemiBold)
                    Text(text.interfaceLanguage, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { language = UiLanguage.Ukrainian }, enabled = language != UiLanguage.Ukrainian) {
                            Text("Українська")
                        }
                        Button(onClick = { language = UiLanguage.English }, enabled = language != UiLanguage.English) {
                            Text("English")
                        }
                    }
                }
            }

            val relayPhase = state.phase as? PeerPhase.RelayRequired
            if (relayPhase != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text.relayRequired, fontWeight = FontWeight.SemiBold)
                        Text(relayPhase.reason, style = MaterialTheme.typography.bodySmall)
                        Text("${text.relay}: ${relayPhase.relayUrl ?: text.notConfigured}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (relayConnected != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text.relayConnected, fontWeight = FontWeight.SemiBold)
                        Text("${text.relay}: ${relayConnected.relayUrl}", style = MaterialTheme.typography.bodySmall)
                        Text("${text.session}: ${relayConnected.relaySessionId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text.packetPassword) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { peer.initialize() } }) {
                    Text(text.initialize)
                }
                Button(onClick = { scope.launch { peer.generateDlpPacket(packetPassword()) } }) {
                    Text(text.createDlp)
                }
            }

            OutlinedTextField(
                value = importPath,
                onValueChange = { importPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text.dlpFilePath) }
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onPickFile { importPath = it } }) {
                        Text(text.pickDlp)
                    }
                    Button(onClick = { scope.launch { peer.importDlpPacket(File(importPath), packetPassword()) } }) {
                        Text(text.importDlp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { peer.connect() } }) {
                        Text(text.connectDirect)
                    }
                }
            }

            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text.relayUrl) }
            )
            OutlinedTextField(
                value = relaySessionId,
                onValueChange = { relaySessionId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text.relaySession) }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { peer.connectViaRelay(relayUrl, RelayHandshakeRole.Host) } }) {
                    Text(text.hostRelay)
                }
                Button(
                    onClick = {
                        scope.launch {
                            peer.connectViaRelay(relayUrl, RelayHandshakeRole.Guest, relaySessionId.ifBlank { null })
                        }
                    }
                ) {
                    Text(text.joinRelay)
                }
            }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text.message) }
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val currentMessage = message
                            message = ""
                            scope.launch { peer.sendText(currentMessage) }
                        },
                        enabled = message.isNotBlank()
                    ) {
                        Text(text.sendText)
                    }
                    Button(onClick = { onPickFile { sendFilePath = it } }) {
                        Text(text.pickFile)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { peer.sendFile(File(sendFilePath)) } },
                        enabled = sendFilePath.isNotBlank()
                    ) {
                        Text(text.sendFile)
                    }
                    Button(onClick = { peer.cancelFileTransfers() }) {
                        Text(text.cancelTransfers)
                    }
                }
            }

            if (sendFilePath.isNotBlank()) {
                Text(sendFilePath, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${text.sentMessages}: ${state.sentMessages}")
                    Text("${text.receivedMessages}: ${state.receivedMessages}")
                    Text("${text.sentBytes}: ${state.sentBytes}")
                    Text("${text.receivedBytes}: ${state.receivedBytes}")
                }
            }

            if (transfers.isNotEmpty()) {
                Text(text.transfers, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    transfers.take(4).forEach { transfer ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${transfer.direction.label(text)}: ${transfer.name}", fontWeight = FontWeight.SemiBold)
                                LinearProgressIndicator(
                                    progress = { transfer.percent / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "${transfer.percent}% - ${transfer.completedBytes}/${transfer.totalBytes} ${text.bytes}${if (transfer.complete) " - ${text.complete}" else ""}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            Text(text.activity, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

private enum class UiLanguage {
    Ukrainian,
    English
}

private data class UiText(
    val settings: String,
    val interfaceLanguage: String,
    val packetPassword: String,
    val initialize: String,
    val createDlp: String,
    val dlpFilePath: String,
    val pickDlp: String,
    val importDlp: String,
    val connectDirect: String,
    val relayUrl: String,
    val relaySession: String,
    val hostRelay: String,
    val joinRelay: String,
    val message: String,
    val sendText: String,
    val pickFile: String,
    val sendFile: String,
    val cancelTransfers: String,
    val relayRequired: String,
    val relayConnected: String,
    val relay: String,
    val session: String,
    val notConfigured: String,
    val sentMessages: String,
    val receivedMessages: String,
    val sentBytes: String,
    val receivedBytes: String,
    val transfers: String,
    val activity: String,
    val activityDlpPacket: String,
    val activityIncomingText: String,
    val activityIncomingFile: String,
    val activitySendFailed: String,
    val activityConnectionLost: String,
    val sending: String,
    val receiving: String,
    val bytes: String,
    val complete: String,
    val phaseIdle: String,
    val phaseGatheringNetwork: String,
    val phaseReady: String,
    val phasePacketGenerated: String,
    val phaseAwaitingConnection: String,
    val phaseConnected: String,
    val phaseRelayRequired: String,
    val phaseRelayConnected: String,
    val phaseError: String
)

private fun uiText(language: UiLanguage): UiText = when (language) {
    UiLanguage.Ukrainian -> UiText(
        settings = "Налаштування",
        interfaceLanguage = "Мова інтерфейсу",
        packetPassword = "Пароль пакета",
        initialize = "Підготувати",
        createDlp = "Створити .dlp",
        dlpFilePath = "Шлях до .dlp файла",
        pickDlp = "Вибрати .dlp",
        importDlp = "Імпортувати",
        connectDirect = "З'єднати напряму",
        relayUrl = "Адреса relay-сервера",
        relaySession = "Код relay-сесії",
        hostRelay = "Створити relay",
        joinRelay = "Приєднатися",
        message = "Повідомлення",
        sendText = "Надіслати текст",
        pickFile = "Вибрати файл",
        sendFile = "Надіслати файл",
        cancelTransfers = "Скасувати передачі",
        relayRequired = "Потрібен relay",
        relayConnected = "Relay підключено",
        relay = "Relay",
        session = "Сесія",
        notConfigured = "не налаштовано",
        sentMessages = "Надіслано повідомлень",
        receivedMessages = "Отримано повідомлень",
        sentBytes = "Надіслано байтів",
        receivedBytes = "Отримано байтів",
        transfers = "Передачі файлів",
        activity = "Журнал подій",
        activityDlpPacket = "DLP пакет",
        activityIncomingText = "Вхідний текст",
        activityIncomingFile = "Вхідний файл",
        activitySendFailed = "Не вдалося надіслати",
        activityConnectionLost = "Зв'язок втрачено",
        sending = "Надсилання",
        receiving = "Отримання",
        bytes = "байтів",
        complete = "завершено",
        phaseIdle = "Очікування",
        phaseGatheringNetwork = "Перевірка мережі",
        phaseReady = "Готово",
        phasePacketGenerated = "Пакет створено",
        phaseAwaitingConnection = "Очікування з'єднання з",
        phaseConnected = "Підключено до",
        phaseRelayRequired = "Потрібен relay для",
        phaseRelayConnected = "Relay-з'єднання з",
        phaseError = "Помилка"
    )
    UiLanguage.English -> UiText(
        settings = "Settings",
        interfaceLanguage = "Interface language",
        packetPassword = "Packet password",
        initialize = "Initialize",
        createDlp = "Create .dlp",
        dlpFilePath = "DLP file path",
        pickDlp = "Pick .dlp",
        importDlp = "Import",
        connectDirect = "Connect directly",
        relayUrl = "Relay server URL",
        relaySession = "Relay session code",
        hostRelay = "Host relay",
        joinRelay = "Join relay",
        message = "Message",
        sendText = "Send text",
        pickFile = "Pick file",
        sendFile = "Send file",
        cancelTransfers = "Cancel transfers",
        relayRequired = "Relay required",
        relayConnected = "Relay connected",
        relay = "Relay",
        session = "Session",
        notConfigured = "not configured",
        sentMessages = "Sent messages",
        receivedMessages = "Received messages",
        sentBytes = "Sent bytes",
        receivedBytes = "Received bytes",
        transfers = "Transfers",
        activity = "Activity",
        activityDlpPacket = "DLP packet",
        activityIncomingText = "Incoming text",
        activityIncomingFile = "Incoming file",
        activitySendFailed = "Send failed",
        activityConnectionLost = "Connection lost",
        sending = "Sending",
        receiving = "Receiving",
        bytes = "bytes",
        complete = "complete",
        phaseIdle = "Idle",
        phaseGatheringNetwork = "Gathering network",
        phaseReady = "Ready",
        phasePacketGenerated = "Packet generated",
        phaseAwaitingConnection = "Awaiting connection with",
        phaseConnected = "Connected to",
        phaseRelayRequired = "Relay required for",
        phaseRelayConnected = "Relay connected to",
        phaseError = "Error"
    )
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

private fun FileTransferDirection.label(text: UiText): String = when (this) {
    FileTransferDirection.Sending -> text.sending
    FileTransferDirection.Receiving -> text.receiving
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

private fun phaseText(phase: PeerPhase, text: UiText): String = when (phase) {
    PeerPhase.Idle -> text.phaseIdle
    PeerPhase.GatheringNetwork -> text.phaseGatheringNetwork
    is PeerPhase.Ready -> "${text.phaseReady}: ${phase.localIp}:${phase.udpPort}"
    is PeerPhase.PacketGenerated -> "${text.phasePacketGenerated}: ${phase.dlpFile.name}"
    is PeerPhase.AwaitingConnection -> "${text.phaseAwaitingConnection} ${phase.peerName}"
    is PeerPhase.Connected -> "${text.phaseConnected} ${phase.peerName}"
    is PeerPhase.RelayRequired -> "${text.phaseRelayRequired} ${phase.peerName}"
    is PeerPhase.RelayConnected -> "${text.phaseRelayConnected} ${phase.peerName}"
    is PeerPhase.Error -> "${text.phaseError}: ${phase.reason}"
}
