package com.ikegami.svcam.inference

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
}
