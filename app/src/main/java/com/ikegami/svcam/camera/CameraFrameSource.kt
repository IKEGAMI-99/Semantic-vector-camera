package com.ikegami.svcam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.ikegami.svcam.logging.AppLogger
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CameraFrameSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) : AutoCloseable {
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<((Result<Bitmap>) -> Unit)?>(null)
    private var provider: ProcessCameraProvider? = null

    fun bind(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching {
                provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor) { image -> analyze(image) }
                provider?.unbindAll()
                provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                AppLogger.info("CAMERA", "bound")
            }.onFailure {
                AppLogger.error("CAMERA", "bind_failed", it)
                pending.getAndSet(null)?.invoke(Result.failure(it))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun requestFrame(callback: (Result<Bitmap>) -> Unit) {
        if (!pending.compareAndSet(null, callback)) {
            callback(Result.failure(IllegalStateException("A capture is already pending")))
        }
    }

    private fun analyze(image: ImageProxy) {
        val callback = pending.getAndSet(null)
        if (callback == null) {
            image.close()
            return
        }
        try {
            val bitmap = image.toBitmap()
            val rotation = image.imageInfo.rotationDegrees
            val corrected = if (rotation == 0) bitmap else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            }
            AppLogger.info("CAMERA", "frame_captured", mapOf("width" to corrected.width, "height" to corrected.height, "rotation" to rotation))
            callback(Result.success(corrected))
        } catch (t: Throwable) {
            AppLogger.error("CAMERA", "frame_capture_failed", t)
            callback(Result.failure(t))
        } finally {
            image.close()
        }
    }

    override fun close() {
        provider?.unbindAll()
        analyzerExecutor.shutdownNow()
    }
}
