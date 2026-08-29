package com.ikegami.svcam.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ikegami.svcam.AppController
import com.ikegami.svcam.camera.CameraFrameSource
import com.ikegami.svcam.data.CaptureEntry
import com.ikegami.svcam.logging.AppLogger
import com.ikegami.svcam.logging.LiveLogEvent
import com.ikegami.svcam.sharing.ShareHelper
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
internal fun CameraScreen(controller: AppController, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
    }
    val source = remember(context, lifecycleOwner) { CameraFrameSource(context, lifecycleOwner) }
    var processing by remember { mutableStateOf(false) }
    var processingFinished by remember { mutableStateOf(false) }
    var processingFailed by remember { mutableStateOf(false) }
    var sessionStartedAt by remember { mutableStateOf(Instant.EPOCH) }
    var terminalEvents by remember { mutableStateOf<List<LiveLogEvent>>(emptyList()) }
    var status by remember { mutableStateOf("READY / 896D") }
    var lastCapture by remember { mutableStateOf<CaptureEntry?>(null) }
    val model = controller.modelManager.current()

    DisposableEffect(source) { onDispose { source.close() } }

    LaunchedEffect(processing, sessionStartedAt) {
        if (!processing) return@LaunchedEffect
        AppLogger.events.collect { event ->
            if (!event.time.isBefore(sessionStartedAt)) {
                terminalEvents = (terminalEvents + event).takeLast(180)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (permissionGranted) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            source.bind(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("カメラ権限が必要です")
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("許可する") }
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                shape = RoundedCornerShape(10.dp),
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(status, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                    Text(
                        if (model.ready) "${model.modelName} + ${model.mmprojName}" else "MODEL NOT LOADED",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = lastCapture != null && !processing,
                onClick = { lastCapture?.let { ShareHelper.shareCapture(context, it.file) } },
            ) { Text("Share Decode") }

            Button(
                modifier = Modifier.size(82.dp).clip(CircleShape),
                enabled = permissionGranted && model.ready && !processing,
                onClick = {
                    sessionStartedAt = Instant.now()
                    terminalEvents = emptyList()
                    processingFailed = false
                    processingFinished = false
                    processing = true
                    status = "CAPTURING REALITY"
                    AppLogger.info(
                        "SESSION",
                        "capture_requested",
                        mapOf("schema" to "SVCAM-896-V1"),
                    )

                    source.requestFrame { frameResult ->
                        scope.launch {
                            val bitmap = frameResult.getOrElse { error ->
                                AppLogger.error("SESSION", "processing_failed", error)
                                status = "CAPTURE FAILED"
                                processingFailed = true
                                processingFinished = true
                                snackbar.showSnackbar(error.message ?: "Capture failed")
                                return@launch
                            }

                            status = "UNDERSTANDING"
                            try {
                                val entry = controller.encodeCapture(bitmap)
                                lastCapture = entry
                                status = "MEMORY SAVED / 896D"
                                AppLogger.info(
                                    "SESSION",
                                    "semantic_memory_complete",
                                    mapOf(
                                        "objects" to entry.objectCount,
                                        "file" to entry.file.name,
                                    ),
                                )
                                processingFinished = true
                            } catch (error: Throwable) {
                                AppLogger.error("SESSION", "processing_failed", error)
                                status = "ENCODE FAILED"
                                processingFailed = true
                                processingFinished = true
                                snackbar.showSnackbar(error.message ?: error::class.java.simpleName)
                            }
                        }
                    }
                },
            ) { Text("896D") }

            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(if (lastCapture == null) "NO MEMORY" else "LAST SAVED")
                Text(
                    lastCapture?.let { "${it.objectCount} objects" } ?: "元画像は保存しません",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

    if (processing) {
        Dialog(
            onDismissRequest = {
                if (processingFinished) {
                    processing = false
                    terminalEvents = emptyList()
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = processingFinished,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            ProcessingConsole(
                events = terminalEvents,
                startedAt = sessionStartedAt,
                finished = processingFinished,
                failed = processingFailed,
                onClose = {
                    processing = false
                    terminalEvents = emptyList()
                },
            )
        }
    }
}
