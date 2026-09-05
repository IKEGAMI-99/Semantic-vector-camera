package com.ikegami.svcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ikegami.svcam.AppController
import com.ikegami.svcam.BuildConfig
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

@Composable
internal fun LibraryScreen(controller: AppController, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(controller.repository.list()) }

    if (entries.isEmpty()) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
internal fun SettingsScreen(controller: AppController, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf(controller.modelManager.current()) }
    var busy by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    fun importPart(uri: android.net.Uri?, part: ModelPart) {
        if (uri == null) return
        scope.launch {
            busy = true
            try {
                controller.unloadModel()
                model = controller.modelManager.import(uri, part)
                snackbar.showSnackbar(if (part == ModelPart.MODEL) "Model GGUFを読み込みました" else "Q8_0 mmproj GGUFを読み込みました")
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
                KeyValue("Projector", if (model.q8Projector) "Q8_0" else "Q8_0 REQUIRED")
                KeyValue("Backend", "VULKAN")
                KeyValue("Status", if (model.ready) "READY" else "MODEL + Q8_0 MMPROJ REQUIRED")
                Text(
                    "この版はBF16 projectorを使用しません。Gemma 4 E4B用のQ8_0 mmprojを読み込んでください。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { modelPicker.launch(arrayOf("*/*")) }) { Text("Model GGUF") }
                    Button(enabled = !busy, onClick = { mmprojPicker.launch(arrayOf("*/*")) }) { Text("Q8_0 mmproj") }
                }
                OutlinedButton(
                    enabled = !busy && (model.modelPath.isNotBlank() || model.mmprojPath.isNotBlank()),
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                controller.unloadModel()
                                controller.modelManager.clear()
                                model = controller.modelManager.current()
                            } finally {
                                busy = false
                            }
                        }
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
                KeyValue("Current", BuildConfig.VERSION_NAME)
                KeyValue("Channel", if (BuildConfig.DEBUG) "SIGNED DEBUG" else "RELEASE")
                KeyValue("Package", context.packageName)
                KeyValue(
                    "Install permission",
                    if (controller.updateManager.canRequestPackageInstalls()) "READY" else "NEEDS ALLOW",
                )
                Text(
                    "Debug/Releaseとも同じpackageと署名鍵を使う個人配布モードです。新しいGitHub Releaseへそのまま上書き更新できます。",
                    style = MaterialTheme.typography.bodySmall,
                )
                FilledTonalButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val info = controller.updateManager.check()
                                updateInfo = info
                                snackbar.showSnackbar(info.status)
                            } catch (error: Throwable) {
                                AppLogger.error("UPDATE", "check_failed", error)
                                snackbar.showSnackbar(error.message ?: "Update check failed")
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text("GitHub Releasesを確認") }
                updateInfo?.let { info ->
                    KeyValue("Latest", info.version.ifBlank { "未公開" })
                    Text(info.status, style = MaterialTheme.typography.bodySmall)
                    if (info.installable) {
                        Button(
                            enabled = !busy,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        controller.updateManager.downloadAndInstall(info)
                                    } catch (error: Throwable) {
                                        AppLogger.error("UPDATE", "install_failed", error)
                                        snackbar.showSnackbar(error.message ?: "Update failed")
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        ) { Text("Download & Install") }
                    } else if (info.available || info.version.isBlank()) {
                        OutlinedButton(onClick = { controller.updateManager.openReleasePage() }) {
                            Text("Releaseページを開く")
                        }
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
