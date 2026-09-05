package com.ikegami.svcam.inference

import com.ikegami.svcam.logging.AppLogger

data class NativeProgressSnapshot(
    val event: String,
    val current: Int,
    val total: Int,
    val elapsedMs: Long,
)

object NativeGemmaBridge {
    init {
        System.loadLibrary("svcam_native")
    }

    @Volatile
    private var lastProgress = NativeProgressSnapshot(
        event = "idle",
        current = 0,
        total = 0,
        elapsedMs = 0L,
    )

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
    ): ByteArray

    external fun nativeDestroy(handle: Long)

    fun resetProgress() {
        lastProgress = NativeProgressSnapshot("idle", 0, 0, 0L)
    }

    fun progressSnapshot(): NativeProgressSnapshot = lastProgress

    /**
     * Called by the JNI inference loop. Keep a volatile snapshot as well as logging the
     * event so a second Kotlin coroutine can emit heartbeat lines while a long native
     * graph is still executing and JNI itself has nothing new to report.
     */
    fun onNativeProgress(event: String, current: Int, total: Int, elapsedMs: Long) {
        lastProgress = NativeProgressSnapshot(event, current, total, elapsedMs)
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
