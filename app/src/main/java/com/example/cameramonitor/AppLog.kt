package com.example.cameramonitor

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Diagnostic logger that captures in-memory logs and logcat output
 * so users can view and copy live diagnostic logs for debugging.
 */
object AppLog {

    private const val MAX_LOGS = 2000
    private val buffer = Collections.synchronizedList(ArrayList<String>())
    private var listener: ((String) -> Unit)? = null

    init {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        d("AppLog", "=== CamMonitor Session Started: $date ===")
        d("AppLog", "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
        d("AppLog", "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    }

    fun setListener(l: ((String) -> Unit)?) {
        this.listener = l
    }

    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun i(tag: String, msg: String) = log("INFO ", tag, msg)
    fun w(tag: String, msg: String) = log("WARN ", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val extra = if (tr != null) " - ${tr.message}\n${Log.getStackTraceString(tr)}" else ""
        log("ERROR", tag, "$msg$extra")
    }

    private fun log(level: String, tag: String, msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val entry = "$time [$level/$tag] $msg"
        Log.i(tag, msg)
        synchronized(buffer) {
            if (buffer.size >= MAX_LOGS) {
                buffer.removeAt(0)
            }
            buffer.add(entry)
        }
        listener?.invoke(entry)
    }

    /**
     * Reads recent logcat output for this application process
     * so lower-level libuvc / USB events are captured.
     */
    fun refreshFromLogcat() {
        try {
            val pid = android.os.Process.myPid()
            val process = Runtime.getRuntime().exec("logcat -d -v time --pid=$pid -t 150")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            val newLines = ArrayList<String>()
            while (reader.readLine().also { line = it } != null) {
                line?.let { newLines.add(it) }
            }
            reader.close()
            process.destroy()

            synchronized(buffer) {
                for (l in newLines) {
                    if (buffer.none { it.endsWith(l) }) {
                        if (buffer.size >= MAX_LOGS) buffer.removeAt(0)
                        buffer.add(l)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun getAllLogs(): String = synchronized(buffer) {
        buffer.joinToString("\n")
    }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            buffer.add("$time [INFO /AppLog] Logs cleared.")
        }
    }
}
