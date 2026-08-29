package com.ikegami.svcam.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ModelConfig(
    val modelPath: String,
    val modelName: String,
    val mmprojPath: String,
    val mmprojName: String,
) {
    val q8Projector: Boolean
        get() = mmprojName.contains("Q8_0", ignoreCase = true)

    val ready: Boolean
        get() = modelPath.isNotBlank() && mmprojPath.isNotBlank() && q8Projector &&
            File(modelPath).isFile && File(modelPath).length() > 1024L &&
            File(mmprojPath).isFile && File(mmprojPath).length() > 1024L
}

enum class ModelPart { MODEL, MMPROJ }

class ModelManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("svcam_models", Context.MODE_PRIVATE)
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    fun current(): ModelConfig = ModelConfig(
        modelPath = prefs.getString("model_path", "").orEmpty(),
        modelName = prefs.getString("model_name", "").orEmpty(),
        mmprojPath = prefs.getString("mmproj_path", "").orEmpty(),
        mmprojName = prefs.getString("mmproj_name", "").orEmpty(),
    )

    suspend fun import(uri: Uri, part: ModelPart): ModelConfig = withContext(Dispatchers.IO) {
        val displayName = queryName(uri).ifBlank {
            if (part == ModelPart.MODEL) "model.gguf" else "mmproj.gguf"
        }
        require(displayName.lowercase().endsWith(".gguf")) { "Select a .gguf file" }
        if (part == ModelPart.MMPROJ) {
            require(displayName.contains("Q8_0", ignoreCase = true)) {
                "Q8_0 mmprojを選択してください。BF16 projectorはAndroidで非常に遅いため、このVulkan版では使用しません。"
            }
        }

        val target = File(modelsDir, if (part == ModelPart.MODEL) "model.gguf" else "mmproj.gguf")
        val temp = File(modelsDir, target.name + ".importing")
        temp.delete()

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected file" }
            temp.outputStream().buffered(16 * 1024 * 1024).use { output ->
                input.copyTo(output, 16 * 1024 * 1024)
            }
        }
        require(temp.length() > 1024L) { "Selected GGUF file is unexpectedly small" }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Failed to finalize imported model" }

        prefs.edit().apply {
            if (part == ModelPart.MODEL) {
                putString("model_path", target.absolutePath)
                putString("model_name", displayName)
            } else {
                putString("mmproj_path", target.absolutePath)
                putString("mmproj_name", displayName)
            }
        }.apply()
        current()
    }

    fun clear() {
        val config = current()
        config.modelPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        config.mmprojPath.takeIf { it.isNotBlank() }?.let { File(it).delete() }
        prefs.edit().clear().apply()
    }

    private fun queryName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }
}
