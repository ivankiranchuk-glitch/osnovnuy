package com.kirivsoft.directlink.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.kirivsoft.directlink.ui.DirectLinkApp
import com.kirivsoft.directlink.ui.theme.DirectLinkTheme
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    runCatching {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    }

    application {
        val controller = remember { DesktopPeerController() }
        LaunchedEffect(Unit) {
            controller.initialize()
        }

        Window(
            title = "DirectLink",
            state = rememberWindowState(width = 420.dp, height = 780.dp),
            onCloseRequest = {
                controller.close()
                exitApplication()
            }
        ) {
            window.minimumSize = java.awt.Dimension(380, 640)

            DirectLinkTheme {
                DirectLinkApp(
                    peer = controller.peer,
                    onShare = { path ->
                        runCatching {
                            Desktop.getDesktop().open(File(path).parentFile)
                        }
                    },
                    onPickFile = { callback ->
                        SwingUtilities.invokeLater {
                            val chooser = JFileChooser().apply {
                                dialogTitle = "Choose a file"
                                isMultiSelectionEnabled = false
                            }
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                callback(chooser.selectedFile.absolutePath)
                            }
                        }
                    }
                )
            }
        }
    }
}
