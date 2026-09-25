package com.pdfchemy.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.pdfchemy.desktop.i18n.DesktopLocalization
import com.pdfchemy.desktop.ui.DesktopApp
import com.pdfchemy.desktop.ui.DesktopFileDialog
import com.pdfchemy.desktop.ui.theme.DesktopTheme
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main(args: Array<String>) = application {
    // Best-effort garbage collection of stale installer artifacts
    com.pdfchemy.desktop.engine.DesktopUpdateManager.sweepStaleInstallers()

    val langArg = args.firstOrNull { it.startsWith("--lang=", ignoreCase = true) }?.substringAfter("=")
        ?: args.firstOrNull { it.startsWith("--locale=", ignoreCase = true) }?.substringAfter("=")
    val forceSetup = args.any { it.equals("--setup", ignoreCase = true) || it.equals("-s", ignoreCase = true) }

    val shouldShowSetup = remember { DesktopLocalization.initFromCli(langArg, forceSetup) }
    val currentLang by DesktopLocalization.currentLanguageState
    val strings = DesktopLocalization.strings

    var droppedFile by remember {
        mutableStateOf(args.firstOrNull { it.lowercase().endsWith(".pdf") }?.let { File(it) })
    }
    var isDarkTheme by remember { mutableStateOf(true) }
    var currentTab by remember { mutableStateOf(com.pdfchemy.desktop.ui.DesktopNavTab.HOME) }

    val windowState = rememberWindowState(
        width = 1240.dp,
        height = 840.dp,
        position = WindowPosition.PlatformDefault
    )

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "${strings.appTitle} — ${strings.homeHeroTitle}",
        icon = painterResource("icons/linux/icon.png"),
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                when {
                    // Ctrl+O: Open File
                    keyEvent.isCtrlPressed && keyEvent.key == Key.O -> {
                        val file = DesktopFileDialog.openPdf()
                        if (file != null) {
                            droppedFile = file
                        }
                        true
                    }
                    // Ctrl+D: Toggle Dark / Light Theme
                    keyEvent.isCtrlPressed && keyEvent.key == Key.D -> {
                        isDarkTheme = !isDarkTheme
                        true
                    }
                    // F11: Toggle True Borderless Full Screen
                    keyEvent.key == Key.F11 -> {
                        windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Fullscreen
                        }
                        true
                    }
                    // Esc: Exit Fullscreen only (do NOT switch to Home or close active tool)
                    keyEvent.key == Key.Escape -> {
                        if (windowState.placement == WindowPlacement.Fullscreen) {
                            windowState.placement = WindowPlacement.Floating
                            true
                        } else {
                            false
                        }
                    }
                    // Ctrl+1..7: Switch to tool tabs
                    keyEvent.isCtrlPressed && keyEvent.key == Key.One -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.HOME
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Two -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.COMPRESS
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Three -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.ORGANIZE
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Four -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.CONVERT
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Five -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.READER
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Six -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.SECURITY
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Seven -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.BATCH
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Eight -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.MERGE
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Nine -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.SIGN
                        true
                    }
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Zero -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.COMPARE
                        true
                    }
                    // Ctrl+F: Find / Search text in Reader
                    keyEvent.isCtrlPressed && keyEvent.key == Key.F && currentTab != com.pdfchemy.desktop.ui.DesktopNavTab.READER -> {
                        currentTab = com.pdfchemy.desktop.ui.DesktopNavTab.READER
                        true
                    }
                    // Ctrl+Q: Exit
                    keyEvent.isCtrlPressed && keyEvent.key == Key.Q -> {
                        exitApplication()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    ) {
        // Attach native OS Drag & Drop listener to the AWT Window Frame
        DisposableEffect(window) {
            val dropTarget = object : DropTarget() {
                @Suppress("UNCHECKED_CAST")
                override fun drop(evt: DropTargetDropEvent) {
                    try {
                        evt.acceptDrop(DnDConstants.ACTION_COPY)
                        val droppedData = evt.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                        val pdfFile = droppedData?.firstOrNull { it.name.lowercase().endsWith(".pdf") }
                        if (pdfFile != null && pdfFile.exists()) {
                            droppedFile = pdfFile
                        }
                        evt.dropComplete(true)
                    } catch (e: Exception) {
                        evt.dropComplete(false)
                    }
                }
            }
            window.dropTarget = dropTarget
            onDispose {
                window.dropTarget = null
            }
        }

        DesktopTheme(darkTheme = isDarkTheme) {
            DesktopApp(
                initialFile = droppedFile,
                initialShowSetup = shouldShowSetup,
                isDarkTheme = isDarkTheme,
                onToggleTheme = { isDarkTheme = !isDarkTheme },
                currentTab = currentTab,
                onTabChange = { currentTab = it },
                isFullScreen = windowState.placement == WindowPlacement.Fullscreen,
                onToggleFullScreen = {
                    windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) {
                        WindowPlacement.Floating
                    } else {
                        WindowPlacement.Fullscreen
                    }
                }
            )
        }
    }
}
