package com.ikegami.svcam

import android.app.Application
import com.ikegami.svcam.logging.AppLogger

class SemanticCameraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { AppLogger.recordCrash(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
