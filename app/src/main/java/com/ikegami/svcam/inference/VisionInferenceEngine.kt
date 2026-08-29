package com.ikegami.svcam.inference

import android.graphics.Bitmap
import com.ikegami.svcam.model.ModelConfig

data class InferenceResult(
    val rawText: String,
    val durationMs: Long,
)

interface VisionInferenceEngine : AutoCloseable {
    suspend fun ensureLoaded(config: ModelConfig)
    suspend fun analyze(bitmap: Bitmap, prompt: String): InferenceResult
    fun isLoaded(): Boolean
    override fun close()
}
