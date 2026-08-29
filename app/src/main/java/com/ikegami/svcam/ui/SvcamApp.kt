package com.ikegami.svcam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ikegami.svcam.AppController
import com.ikegami.svcam.camera.CameraFrameSource
import com.ikegami.svcam.data.CaptureEntry
import com.ikegami.svcam.logging.AppLogger
import com.ikegami.svcam.model.ModelPart
import com.ikegami.svcam.sharing.ShareHelper
import com.ikegami.svcam.update.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

@Composable
private fun CameraScreen(controller: AppController, snackbar: SnackbarHostState) {
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
    var status by remember { mutableStateOf("READY / 896D") }
    var lastCapture by remember { mutableStateOf<CaptureEntry?>(null) }
    val model = controller.modelManager.current()

    DisposableEffect(source) { onDispose { source.close() } }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
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
                    Text(status, style = MaterialTheme.typography.labelLarge)
                    Text(
                        if (model.ready) "${model.modelName} + ${model.mmprojName}" else "MODEL NOT LOADED",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            if (processing) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("UNDERSTANDING → ENCODING 896D")
                    }
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
                    processing = true
                    status = "CAPTURING REALITY"
                    source.requestFrame { frameResult ->
                        scope.launch {
                            val bitmap = frameResult.getOrElse { error ->
                                status = "CAPTURE FAILED"
                                snackbar.showSnackbar(error.message ?: "Capture failed")
                                processing = false
                                return@launch
                            }
                            status = "UNDERSTANDING"
                            try {
                                val entry = controller.encodeCapture(bitmap)
                                lastCapture = entry
                                status = "MEMORY SAVED / 896D"
                            } catch (error: Throwable) {
                                status = "ENCODE FAILED"
                                snackbar.showSnackbar(error.message ?: error::class.java.simpleName)
                            } finally {
                                processing = false
                            }
                        }
                    }
                },
            ) { Text("896D") }

            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(if (lastCapture == null) "NO MEMORY" else "LAST SAVED")
                Text(lastCapture?.let { "${it.objectCount} objects" } ?: "元画像は保存しません", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun LibraryScreen(controller: AppController, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(controller.repository.list()) }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("まだVector Memoryはありません")
                Text("写真ではなく896次元がここに溜まります。", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.height(2.dp)) }
        items(entries, key = { it.file.absolutePath }) { entry ->
            CaptureCard(
                entry = entry,
                onShare = { ShareHelper.shareCapture(context, entry.file) },
                onDelete = {
                    if (controller.repository.delete(entry.file)) entries = controller.repository.list()
                    else scope.launch { snackbar.showSnackbar("削除できませんでした") }
                },
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun CaptureCard(entry: CaptureEntry, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatInstant(entry.createdAt), style = MaterialTheme.typography.titleMedium)
                Text("896D", style = MaterialTheme.typography.labelLarge)
            }
            Text("${entry.objectCount} objects · inference ${entry.inferenceMs} ms", style = MaterialTheme.typography.bodySmall)
            if (entry.topSemantics.isNotEmpty()) {
                Text(
                    entry.topSemantics.joinToString("   ") { (name, value) -> "$name ${"%.2f".format(value)}" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onShare) { Text("AIで復号") }
                OutlinedButton(onClick = onDelete) { Text("削除") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(controller: AppController, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf(controller.modelManager.current()) }
    var busy by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    fun importPart(uri: android.net.Uri?, part: ModelPart) {
        if (uri == null) return
        scope.launch {
            busy = true
            controller.unloadModel()
            try {
                model = controller.modelManager.import(uri, part)
                snackbar.showSnackbar(if (part == ModelPart.MODEL) "Model GGUFを読み込みました" else "mmproj GGUFを読み込みました")
            } catch (error: Throwable) {
                snackbar.showSnackbar(error.message ?: "GGUF import failed")
            } finally {
                busy = false
            }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importPart(it, ModelPart.MODEL) }
    val mmprojPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importPart(it, ModelPart.MMPROJ) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("Vision Model") {
                KeyValue("Model", model.modelName.ifBlank { "未設定" })
                KeyValue("mmproj", model.mmprojName.ifBlank { "未設定" })
                KeyValue("Status", if (model.ready) "READY" else "2 files required")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Model GGUF") }
                    Button(enabled = !busy, onClick = { mmprojPicker.launch(arrayOf("*/*")) }) { Text("mmproj GGUF") }
                }
                OutlinedButton(
                    enabled = !busy && model.ready,
                    onClick = {
                        controller.unloadModel()
                        controller.modelManager.clear()
                        model = controller.modelManager.current()
                    },
                ) { Text("モデルを削除") }
            }
        }

        item {
            SectionCard("Semantic Format") {
                KeyValue("Schema", "SVCAM-896-V1")
                KeyValue("Global", "256D")
                KeyValue("Objects", "16 × 32D = 512D")
                KeyValue("Relations", "128D")
                Text("通常撮影では元画像をストレージへ保存しません。", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            SectionCard("App Update") {
                FilledTonalButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val info = controller.updateManager.check()
                                updateInfo = info
                                snackbar.showSnackbar(if (info.available) "v${info.version} が利用できます" else "最新版です")
                            } catch (error: Throwable) {
                                snackbar.showSnackbar(error.message ?: "Update check failed")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("GitHub Releasesを確認") }
                updateInfo?.let { info ->
                    KeyValue("Latest", info.version.ifBlank { "-" })
                    if (info.available) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        controller.updateManager.downloadAndInstall(info)
                                    } catch (error: Throwable) {
                                        snackbar.showSnackbar(error.message ?: "Update failed")
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        ) { Text("Download & Install") }
                    }
                }
            }
        }

        item {
            SectionCard("Diagnostics") {
                Text(AppLogger.diagnostics(), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val zip = withContext(Dispatchers.IO) { AppLogger.exportZip() }
                                ShareHelper.shareLogZip(context, zip)
                            }
                        },
                    ) { Text("ログ書き出し") }
                    OutlinedButton(onClick = { AppLogger.clear() }) { Text("Clear") }
                }
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SVCAM diagnostics", AppLogger.diagnostics()))
                        scope.launch { snackbar.showSnackbar("Diagnosticsをコピーしました") }
                    },
                ) { Text("Diagnosticsをコピー") }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatInstant(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrElse { value }
