package com.ikegami.svcam.inference

import android.graphics.Bitmap
import com.ikegami.svcam.logging.AppLogger
import com.ikegami.svcam.model.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

class GemmaGgufEngine : VisionInferenceEngine {
    private val nativeLock = Any()
    private var handle: Long = 0L
    private var loadedKey: String? = null

    override suspend fun ensureLoaded(config: ModelConfig) = withContext(Dispatchers.IO) {
        require(config.ready) { "Import both Gemma GGUF and its mmproj GGUF in Settings" }
        val key = "${config.modelPath}|${config.mmprojPath}"

        synchronized(nativeLock) {
            if (handle != 0L && loadedKey == key) return@synchronized

            closeLocked()
            val threads = max(2, Runtime.getRuntime().availableProcessors() - 2).coerceAtMost(8)
            AppLogger.info(
                "GEMMA",
                "model_load_start",
                mapOf(
                    "model" to config.modelName,
                    "mmproj" to config.mmprojName,
                    "threads" to threads,
                    "n_ctx" to CONTEXT_SIZE,
                ),
            )
            val start = System.nanoTime()
            handle = NativeGemmaBridge.nativeCreate(
                modelPath = config.modelPath,
                mmprojPath = config.mmprojPath,
                nThreads = threads,
                nCtx = CONTEXT_SIZE,
            )
            check(handle != 0L) { "Native Gemma engine returned a null handle" }
            loadedKey = key
            AppLogger.info("GEMMA", "model_load_complete", mapOf("duration_ms" to elapsedMs(start)))
        }
    }

    override suspend fun analyze(bitmap: Bitmap, prompt: String): InferenceResult = withContext(Dispatchers.Default) {
        AppLogger.info(
            "IMAGE",
            "vision_frame_prepare",
            mapOf("width" to bitmap.width, "height" to bitmap.height),
        )
        val prepared = scaleForVision(bitmap)
        AppLogger.info(
            "IMAGE",
            "vision_frame_prepared",
            mapOf(
                "width" to prepared.width,
                "height" to prepared.height,
                "scaled" to (prepared !== bitmap),
                "max_side" to VISION_MAX_SIDE,
            ),
        )
        val rgb = bitmapToRgb(prepared)
        AppLogger.info("IMAGE", "rgb_buffer_ready", mapOf("bytes" to rgb.size))

        val start = System.nanoTime()
        AppLogger.info(
            "GEMMA",
            "inference_start",
            mapOf(
                "width" to prepared.width,
                "height" to prepared.height,
                "n_predict" to MAX_PREDICT_TOKENS,
            ),
        )
        try {
            synchronized(nativeLock) {
                check(handle != 0L) { "Gemma model is not loaded" }
                val text = NativeGemmaBridge.nativeAnalyze(
                    handle = handle,
                    width = prepared.width,
                    height = prepared.height,
                    rgb = rgb,
                    prompt = prompt,
                    nPredict = MAX_PREDICT_TOKENS,
                )
                val duration = elapsedMs(start)
                AppLogger.info(
                    "GEMMA",
                    "inference_complete",
                    mapOf("duration_ms" to duration, "output_chars" to text.length),
                )
                InferenceResult(text, duration)
            }
        } finally {
            if (prepared !== bitmap) prepared.recycle()
        }
    }

    override fun isLoaded(): Boolean = synchronized(nativeLock) { handle != 0L }

    override fun close() {
        synchronized(nativeLock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        if (handle != 0L) {
            runCatching { NativeGemmaBridge.nativeDestroy(handle) }
            handle = 0L
            loadedKey = null
        }
    }

    private fun scaleForVision(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= VISION_MAX_SIDE) return bitmap
        val ratio = VISION_MAX_SIDE.toFloat() / maxSide.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun bitmapToRgb(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val rgb = ByteArray(pixels.size * 3)
        var out = 0
        pixels.forEach { pixel ->
            rgb[out++] = ((pixel shr 16) and 0xFF).toByte()
            rgb[out++] = ((pixel shr 8) and 0xFF).toByte()
            rgb[out++] = (pixel and 0xFF).toByte()
        }
        return rgb
    }

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000L

    private companion object {
        // The original 8192 ctx / 1200-token / 1024px profile is needlessly expensive
        // for the app's sparse JSON schema on a phone. These values are the mobile-safe profile.
        const val CONTEXT_SIZE = 4096
        const val VISION_MAX_SIDE = 448
        const val MAX_PREDICT_TOKENS = 384
    }
}
