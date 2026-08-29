package com.ikegami.svcam.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ikegami.svcam.AppController

private enum class AppTab(val label: String, val glyph: String) {
    CAMERA("Camera", "◉"),
    LIBRARY("Vectors", "896"),
    SETTINGS("Settings", "⚙"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SvcamApp(controller: AppController) {
    var tab by remember { mutableStateOf(AppTab.CAMERA) }
    val snackbar = remember { SnackbarHostState() }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Semantic Vector Camera")
                            Text("SVCAM-896-V1", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    AppTab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(item.glyph) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (tab) {
                    AppTab.CAMERA -> CameraScreen(controller, snackbar)
                    AppTab.LIBRARY -> LibraryScreen(controller, snackbar)
                    AppTab.SETTINGS -> SettingsScreen(controller, snackbar)
                }
            }
        }
    }
}
