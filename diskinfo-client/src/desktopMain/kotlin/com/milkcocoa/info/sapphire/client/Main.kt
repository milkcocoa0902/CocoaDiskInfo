package com.milkcocoa.info.sapphire.client

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.milkcocoa.info.sapphire.client.ui.CocoaDiskInfoApp

/** Starts the desktop window and lets Compose own the process lifecycle. */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "CocoaDiskInfo",
    ) {
        CocoaDiskInfoApp()
    }
}
