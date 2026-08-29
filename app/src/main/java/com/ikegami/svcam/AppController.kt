package com.ikegami.svcam

import android.content.Context
import android.graphics.Bitmap
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
            val scene = StructuredScene.parse(inference.rawText)
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
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun unloadModel() = engine.close()

    override fun close() = engine.close()
}
