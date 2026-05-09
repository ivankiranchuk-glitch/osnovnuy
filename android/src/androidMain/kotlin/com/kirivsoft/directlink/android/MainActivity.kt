package com.kirivsoft.directlink.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import com.kirivsoft.directlink.ui.DirectLinkApp
import com.kirivsoft.directlink.ui.theme.DirectLinkTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: NetworkPeerViewModel by viewModels()
    private var filePickerCallback: ((String) -> Unit)? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            copyUriToCache(uri)?.let { file ->
                filePickerCallback?.invoke(file.absolutePath)
            }
        }
        filePickerCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        handleIncomingIntent(intent)

        setContent {
            DirectLinkTheme {
                DirectLinkApp(
                    peer = vm.peer,
                    onShare = ::shareFile,
                    onPickFile = { callback ->
                        filePickerCallback = callback
                        filePicker.launch("*/*")
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return

        copyUriToCache(uri)?.let { vm.handleIncomingDlpFile(it.absolutePath) }
    }

    private fun shareFile(path: String) {
        val file = File(path)
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, "Share DirectLink packet"))
    }

    private fun copyUriToCache(uri: Uri): File? = runCatching {
        val file = File(cacheDir, "incoming_${System.currentTimeMillis()}.dlp")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        file
    }.getOrNull()
}
