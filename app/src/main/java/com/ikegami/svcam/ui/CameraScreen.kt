package com.ikegami.svcam.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.roundToInt

private const val GALLERY_DECODE_MAX_SIDE = 1024

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

    fun beginSession(initialStatus: String) {
        sessionStartedAt = Instant.now()
        terminalEvents = emptyList()
        processingFailed = false
        processingFinished = false
        processing = true
        status = initialStatus
    }

    suspend fun finishFailure(error: Throwable, failureStatus: String) {
        AppLogger.error("SESSION", "processing_failed", error)
        status = failureStatus
        processingFailed = true
        processingFinished = true
        snackbar.showSnackbar(error.message ?: error::class.java.simpleName)
    }

    suspend fun processBitmap(bitmap: Bitmap) {
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
            finishFailure(error, "ENCODE FAILED")
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || processing) return@rememberLauncherForActivityResult

        beginSession("IMPORTING GALLERY")
        AppLogger.info(
            "SESSION",
            "gallery_import_requested",
            mapOf(
                "schema" to "SVCAM-896-V1",
                "uri_scheme" to uri.scheme.orEmpty(),
            ),
        )

        scope.launch {
            val bitmap = try {
                withContext(Dispatchers.IO) { decodeGalleryBitmap(context, uri) }
            } catch (error: Throwable) {
                AppLogger.error("IMAGE", "gallery_import_failed", error)
                finishFailure(error, "IMPORT FAILED")
                return@launch
            }

            AppLogger.info(
                "IMAGE",
                "gallery_image_loaded",
                mapOf(
                    "width" to bitmap.width,
                    "height" to bitmap.height,
                    "config" to bitmap.config?.name.orEmpty(),
                ),
            )
            processBitmap(bitmap)
        }
    }

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
                    Text(
                        "Galleryからの読み込みはカメラ権限なしでも使えます",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
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

        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
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
                        beginSession("CAPTURING REALITY")
                        AppLogger.info(
                            "SESSION",
                            "capture_requested",
                            mapOf("schema" to "SVCAM-896-V1"),
                        )

                        source.requestFrame { frameResult ->
                            scope.launch {
                                val bitmap = frameResult.getOrElse { error ->
                                    finishFailure(error, "CAPTURE FAILED")
                                    return@launch
                                }
                                processBitmap(bitmap)
                            }
                        }
                    },
                ) { Text("896D") }

                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = model.ready && !processing,
                    onClick = { galleryLauncher.launch("image/*") },
                ) { Text("Gallery") }
            }

            Text(
                lastCapture?.let { "LAST SAVED · ${it.objectCount} objects" } ?: "NO MEMORY · 元画像は保存しません",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
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

private fun decodeGalleryBitmap(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)

        val width = info.size.width
        val height = info.size.height
        val maxSide = maxOf(width, height)
        if (maxSide > GALLERY_DECODE_MAX_SIDE) {
            val scale = GALLERY_DECODE_MAX_SIDE.toFloat() / maxSide.toFloat()
            decoder.setTargetSize(
                (width * scale).roundToInt().coerceAtLeast(1),
                (height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }
}
