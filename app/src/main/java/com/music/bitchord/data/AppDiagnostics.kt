package com.music.bitchord.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import android.os.Build
import android.util.AtomicFile
import com.music.bitchord.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Collects bounded diagnostics for an explicit user export or submission. */
internal object AppDiagnostics {
    private const val MAX_EVENTS = 24
    private const val MAX_FRAMES = 16
    private val events = ArrayDeque<String>()
    private val handlingCrash = AtomicBoolean()

    fun install(context: Context) {
        val crash = crashFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (handlingCrash.compareAndSet(false, true)) {
                    runCatching {
                        val snapshot = reportHeader() + "\n\n" +
                            formatEvent("Uncaught exception", error) + "\n" + recentEvents()
                        val stream = crash.startWrite()
                        try {
                            stream.write(snapshot.toByteArray(Charsets.UTF_8))
                            crash.finishWrite(stream)
                        } catch (failure: Throwable) {
                            crash.failWrite(stream)
                            throw failure
                        }
                    }
                }
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    // Callers provide fixed labels. Exception messages and filenames can contain
    // paths, account data or signed URLs, so only code symbols are included.
    fun record(label: String, error: Throwable? = null) {
        val event = formatEvent(label, error)
        synchronized(events) {
            if (events.lastOrNull() == event) return
            if (events.size == MAX_EVENTS) events.removeFirst()
            events.addLast(event)
        }
    }

    fun report(context: Context): String = buildString {
        appendLine(reportHeader())
        val crash = runCatching { crashFile(context).readFully().toString(Charsets.UTF_8) }.getOrNull()
        if (!crash.isNullOrBlank()) {
            appendLine()
            appendLine("Last recorded crash")
            appendLine(crash)
        }
        val exits = systemExits(context)
        if (exits.isNotEmpty()) {
            appendLine()
            appendLine("Recent Android process exits")
            exits.forEach { appendLine(it) }
        }
        val recent = recentEvents()
        if (recent.isNotBlank()) {
            appendLine()
            appendLine("Recent app events")
            appendLine(recent)
        }
    }

    private val submitUrl = runCatching {
        val base = URI(BuildConfig.DIAGNOSTICS_SERVER.trim().trimEnd('/'))
        require(base.scheme == "https" && base.host != null && base.userInfo == null &&
            base.query == null && base.fragment == null)
        URI(base.toString() + "/api/diagnostics").toURL()
    }.getOrNull()

    val canSubmit: Boolean get() = submitUrl != null

    /** The caller runs this on the IO dispatcher. */
    fun submit(context: Context): Int {
        val url = checkNotNull(submitUrl)
        val connection = url.openConnection() as HttpsURLConnection
        try {
            val body = JSONObject().put("report", report(context).take(10_000)).toString().toByteArray()
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode == 201)
            return connection.inputStream.bufferedReader().use {
                JSONObject(it.readText()).getInt("number").also { number -> check(number > 0) }
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun formatEvent(label: String, error: Throwable?): String = buildString {
        appendLine(label)
        var cause = error
        repeat(3) {
            val current = cause ?: return@buildString
            appendLine(safeSymbol(current.javaClass.name))
            current.stackTrace.take(MAX_FRAMES).forEach { frame ->
                appendLine("  ${safeSymbol(frame.className)}.${safeSymbol(frame.methodName)}:${frame.lineNumber}")
            }
            cause = current.cause?.takeUnless { it === current }
        }
    }.trimEnd()

    private fun recentEvents(): String = synchronized(events) { events.reversed().joinToString("\n\n") }

    private fun crashFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, "last-app-crash.txt"))

    private fun reportHeader(): String =
        "ReChord ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})\n" +
            "Android SDK ${Build.VERSION.SDK_INT}"

    private fun systemExits(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 8).orEmpty().mapNotNull { exit ->
                    val reason = when (exit.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "App crash"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                        ApplicationExitInfo.REASON_ANR -> "App not responding"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "Low memory kill"
                        else -> return@mapNotNull null
                    }
                    "$reason (time: ${exit.timestamp})"
                }
        }.getOrDefault(emptyList())
    }

    private val symbol = Regex("[a-zA-Z0-9_.$<>-]{1,180}")

    private fun safeSymbol(name: String): String = if (symbol.matches(name)) name else "unknown"
}
