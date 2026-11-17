package com.imdevops.smalix

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

// Setup check only: confirms the toolchain builds. Real UI comes in v0.1 feature modules.
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Smalix") {
        MaterialTheme { Text("Smalix v0.1 - structure OK") }
    }
}
