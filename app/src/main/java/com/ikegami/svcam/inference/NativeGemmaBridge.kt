package com.ikegami.svcam.inference

import com.ikegami.svcam.logging.AppLogger

object NativeGemmaBridge {
    init {
        System.loadLibrary("svcam_native")
    }

    external fun nativeCreate(
        modelPath: String,
        mmprojPath: String,
        nThreads: Int,
        nCtx: Int,
    ): Long

    external fun nativeAnalyze(
        handle: Long,
        width: Int,
        height: Int,
        rgb: ByteArray,
        prompt: String,
        nPredict: Int,
    ): String

    external fun nativeDestroy(handle: Long)

    /**
     * Called by the JNI inference loop so the on-screen terminal keeps moving while
     * llama.cpp is inside mtmd evaluation / token generation.
     */
    fun onNativeProgress(event: String, current: Int, total: Int, elapsedMs: Long) {
        AppLogger.info(
            "GEMMA",
            event,
            mapOf(
                "current" to current,
                "total" to total,
                "elapsed_ms" to elapsedMs,
            ),
        )
    }
}
