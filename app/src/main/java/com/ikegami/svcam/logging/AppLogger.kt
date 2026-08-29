package com.ikegami.svcam.logging

import android.content.Context
import android.os.Build
import com.ikegami.svcam.BuildConfig
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object AppLogger {
    private val lock = Any()
    private lateinit var appContext: Context
    private lateinit var logDir: File
    private lateinit var appLog: File
    private lateinit var crashLog: File

    fun init(context: Context) {
        synchronized(lock) {
            if (::appContext.isInitialized) return
            appContext = context.applicationContext
            logDir = File(appContext.filesDir, "logs").apply { mkdirs() }
            appLog = File(logDir, "app.jsonl")
            crashLog = File(logDir, "crash.log")
            info("APP", "logger_initialized", mapOf("version" to BuildConfig.VERSION_NAME))
        }
    }

    fun info(module: String, event: String, fields: Map<String, Any?> = emptyMap()) = write("INFO", module, event, fields)
    fun warn(module: String, event: String, fields: Map<String, Any?> = emptyMap()) = write("WARN", module, event, fields)
    fun error(module: String, event: String, throwable: Throwable? = null, fields: Map<String, Any?> = emptyMap()) {
        val extras = fields.toMutableMap()
        throwable?.let {
            extras["exception"] = it::class.java.name
            extras["message"] = it.message
        }
        write("ERROR", module, event, extras)
    }

    fun recordCrash(thread: Thread, throwable: Throwable) {
        synchronized(lock) {
            if (!::crashLog.isInitialized) return
            crashLog.writeText(
                buildString {
                    appendLine("time=${Instant.now()}")
                    appendLine("thread=${thread.name}")
                    appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
                    appendLine()
                    append(throwable.stackTraceToString())
                }
            )
        }
    }

    fun sizeBytes(): Long = synchronized(lock) {
        if (!::logDir.isInitialized) 0L else logDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun clear() = synchronized(lock) {
        if (!::logDir.isInitialized) return@synchronized
        logDir.listFiles()?.forEach { it.delete() }
        info("APP", "logs_cleared")
    }

    fun diagnostics(): String = buildString {
        appendLine("Semantic Vector Camera ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
        appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("ABI ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("CPU cores ${Runtime.getRuntime().availableProcessors()}")
        appendLine("Log bytes ${sizeBytes()}")
    }

    fun exportZip(): File = synchronized(lock) {
        check(::appContext.isInitialized)
        val outDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val zip = File(outDir, "SVCAM_logs_${System.currentTimeMillis()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            val info = JSONObject().apply {
                put("app_version", BuildConfig.VERSION_NAME)
                put("version_code", BuildConfig.VERSION_CODE)
                put("android", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("abis", Build.SUPPORTED_ABIS.joinToString(","))
                put("cpu_cores", Runtime.getRuntime().availableProcessors())
            }.toString(2).toByteArray()
            output.putNextEntry(ZipEntry("app_info.json"))
            output.write(info)
            output.closeEntry()

            listOf(appLog, crashLog).filter { it.exists() }.forEach { file ->
                output.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        zip
    }

    private fun write(level: String, module: String, event: String, fields: Map<String, Any?>) {
        synchronized(lock) {
            if (!::appLog.isInitialized) return
            val json = JSONObject().apply {
                put("time", Instant.now().toString())
                put("level", level)
                put("module", module)
                put("event", event)
                fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
            }
            appLog.appendText(json.toString() + "\n")
            if (appLog.length() > 5L * 1024L * 1024L) rotate()
        }
    }

    private fun rotate() {
        val previous = File(logDir, "previous.jsonl")
        previous.delete()
        appLog.renameTo(previous)
        appLog = File(logDir, "app.jsonl")
    }
}
