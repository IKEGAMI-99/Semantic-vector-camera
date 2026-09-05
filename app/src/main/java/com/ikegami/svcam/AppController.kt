package com.ikegami.svcam

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ikegami.svcam.data.CaptureEntry
import com.ikegami.svcam.data.SvcamRepository
import com.ikegami.svcam.inference.GemmaGgufEngine
import com.ikegami.svcam.logging.AppLogger
import com.ikegami.svcam.model.ModelManager
import com.ikegami.svcam.semantic.SemanticEncoder
import com.ikegami.svcam.semantic.SemanticPrompt
import com.ikegami.svcam.semantic.StructuredScene
import com.ikegami.svcam.update.UpdateManager

class AppController(context: Context) : AutoCloseable {
    val modelManager = ModelManager(context)
    val repository = SvcamRepository(context)
    val updateManager = UpdateManager(context)
    private val engine = GemmaGgufEngine()

    suspend fun encodeCapture(bitmap: Bitmap): CaptureEntry {
        val config = modelManager.current()
        require(config.ready) { "先に Settings で Gemma 4 の model.gguf と mmproj.gguf を読み込んでください" }

        try {
            engine.ensureLoaded(config)
            val inference = engine.analyze(bitmap, SemanticPrompt.build())

            AppLogger.info(
                "ENCODER",
                "semantic_parse_start",
                mapOf("output_chars" to inference.rawText.length),
            )
            val scene = StructuredScene.parse(inference.rawText)
            AppLogger.info(
                "ENCODER",
                "semantic_parse_complete",
                mapOf(
                    "global_scores" to scene.global.size,
                    "objects" to scene.objects.size,
                    "relation_scores" to scene.relations.size,
                ),
            )

            AppLogger.info("ENCODER", "encoding_start", mapOf("schema" to "SVCAM-896-V1"))
            val vector = SemanticEncoder.encode(scene)
            val flat = vector.flatten()
            check(flat.size == 896) { "Semantic Vector dimension mismatch: ${flat.size}" }

            val min = flat.minOrNull() ?: 0f
            val max = flat.maxOrNull() ?: 0f
            val mean = if (flat.isEmpty()) 0f else flat.average().toFloat()
            AppLogger.info(
                "ENCODER",
                "vector_valid",
                mapOf(
                    "dimensions" to flat.size,
                    "objects" to vector.objectCount,
                    "min" to min,
                    "max" to max,
                    "mean" to mean,
                ),
            )

            return repository.save(
                vector = vector,
                modelName = config.modelName,
                mmprojName = config.mmprojName,
                inferenceMs = inference.durationMs,
            )
        } catch (t: Throwable) {
            AppLogger.error("ENCODER", "capture_failed", t)
            throw t
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
                AppLogger.info("IMAGE", "original_frame_destroyed")
            }
        }
    }

    suspend fun unloadModel() = withContext(Dispatchers.IO) { engine.close() }

    // Native inference holds its lock until the current graph finishes. Activity
    // destruction must never wait for that lock on Android's main thread (ANR).
    override fun close() {
        CoroutineScope(Dispatchers.IO).launch { engine.close() }
    }
}
