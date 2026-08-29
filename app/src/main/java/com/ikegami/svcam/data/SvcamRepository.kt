package com.ikegami.svcam.data

import android.content.Context
import com.ikegami.svcam.logging.AppLogger
import com.ikegami.svcam.semantic.SemanticSchema
import com.ikegami.svcam.semantic.SemanticVector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CaptureEntry(
    val file: File,
    val createdAt: String,
    val objectCount: Int,
    val topSemantics: List<Pair<String, Float>>,
    val inferenceMs: Long,
)

class SvcamRepository(context: Context) {
    private val dir = File(context.filesDir, "svcam").apply { mkdirs() }
    private val fileFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.US)
        .withZone(ZoneId.systemDefault())

    fun save(
        vector: SemanticVector,
        modelName: String,
        mmprojName: String,
        inferenceMs: Long,
    ): CaptureEntry {
        val now = Instant.now()
        val root = JSONObject().apply {
            put("format", "SVCAM")
            put("schema", SemanticSchema.ID)
            put("created_at", now.toString())
            put("dimensions", SemanticSchema.TOTAL_DIMENSIONS)
            put("source_image_saved", false)
            put("encoder", JSONObject().apply {
                put("engine", "llama.cpp/libmtmd")
                put("model", modelName)
                put("mmproj", mmprojName)
                put("inference_ms", inferenceMs)
            })
            put("layout", JSONObject().apply {
                put("global", SemanticSchema.GLOBAL_DIMENSIONS)
                put("object_slots", SemanticSchema.OBJECT_SLOTS)
                put("object_dimensions", SemanticSchema.OBJECT_DIMENSIONS)
                put("relations", SemanticSchema.RELATION_DIMENSIONS)
            })
            put("labels", JSONObject().apply {
                put("global", SemanticSchema.GLOBAL_LABELS.toJsonArray())
                put("object", SemanticSchema.OBJECT_LABELS.toJsonArray())
                put("relations", SemanticSchema.RELATION_LABELS.toJsonArray())
            })
            put("vector", JSONObject().apply {
                put("global", vector.global.toJsonArray())
                put("objects", JSONArray().apply { vector.objects.forEach { put(it.toJsonArray()) } })
                put("relations", vector.relations.toJsonArray())
            })
            put("metadata", JSONObject().apply {
                put("object_count", vector.objectCount)
                put("vector_valid", vector.flatten().size == SemanticSchema.TOTAL_DIMENSIONS)
            })
        }

        val file = File(dir, "capture_${fileFormatter.format(now)}.svcam.json")
        file.writeText(root.toString())
        AppLogger.info("SVCAM", "capture_saved", mapOf("file" to file.name, "bytes" to file.length(), "objects" to vector.objectCount))
        return readEntry(file)
    }

    fun list(): List<CaptureEntry> = dir.listFiles { file -> file.name.endsWith(".svcam.json") }
        ?.sortedByDescending { it.lastModified() }
        ?.mapNotNull { runCatching { readEntry(it) }.getOrNull() }
        .orEmpty()

    fun delete(file: File): Boolean {
        val deleted = file.exists() && file.delete()
        if (deleted) AppLogger.info("SVCAM", "capture_deleted", mapOf("file" to file.name))
        return deleted
    }

    private fun readEntry(file: File): CaptureEntry {
        val root = JSONObject(file.readText())
        val vector = root.getJSONObject("vector").getJSONArray("global")
        val top = SemanticSchema.GLOBAL_LABELS.indices
            .map { index -> SemanticSchema.GLOBAL_LABELS[index] to vector.optDouble(index, 0.0).toFloat() }
            .filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(3)
        return CaptureEntry(
            file = file,
            createdAt = root.optString("created_at"),
            objectCount = root.optJSONObject("metadata")?.optInt("object_count", 0) ?: 0,
            topSemantics = top,
            inferenceMs = root.optJSONObject("encoder")?.optLong("inference_ms", 0L) ?: 0L,
        )
    }

    private fun List<String>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it) } }
    private fun FloatArray.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it.toDouble()) } }
}
