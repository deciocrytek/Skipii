package com.decio.skipii

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Crash reports (0.5.109, GitHub #55: an OPPO K13 Turbo Pro that "keeps closing"). Skipii has no crash
 * service, so a crash only ever reached us as words. [install] (first thing in SkipiiApplication) keeps
 * the stack trace of every uncaught exception in filesDir/crashes (the newest [Kept]), then hands the
 * crash on to Android as before. [report] adds the phone, the build and Android's own record of how the
 * app's last processes ended (getHistoricalProcessExitReasons, Android 11+): low memory, native crashes,
 * ANRs and ROM kills leave no stack trace of ours, only that. Shared from Settings › About › Crash report
 * as Markdown, to paste into an issue. Nothing about the account or what was watched goes in.
 */
object CrashLog {
    private const val Kept = 3
    private const val Exits = 6
    private const val MaxTrace = 12_000
    private lateinit var dir: File
    private lateinit var prefs: android.content.SharedPreferences

    /** When the newest saved crash happened (0 = none). */
    var newest by mutableLongStateOf(0L)
        private set
    private var sharedAt by mutableLongStateOf(0L)
    /** The newest crash was saved after the report was last shared: Settings' About tile says so. */
    val unshared get() = newest > sharedAt

    fun install(context: Context) {
        dir = File(context.filesDir, "crashes")
        prefs = context.getSharedPreferences("crashes", Context.MODE_PRIVATE)
        newest = files().firstOrNull()?.lastModified() ?: 0L
        sharedAt = prefs.getLong(KEY_SHARED, 0L)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Never in the way of the crash itself: whatever happens here, Android still gets it.
            try { write(thread, error) } catch (_: Throwable) {}
            if (previous != null) previous.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); exitProcess(10) }
        }
    }

    fun markShared() {
        sharedAt = System.currentTimeMillis()
        prefs.edit().putLong(KEY_SHARED, sharedAt).apply()
    }

    private fun files(): List<File> =
        dir.listFiles { f -> f.name.startsWith("crash-") }.orEmpty().sortedByDescending { it.lastModified() }

    private fun write(thread: Thread, error: Throwable) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val rt = Runtime.getRuntime()
        val up = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000
        File(dir, "crash-$now.txt").writeText(buildString {
            appendLine("### Crash ${stamp(now)}")
            appendLine("Thread \"${thread.name}\" · up ${up} s · heap ${mb(rt.totalMemory() - rt.freeMemory())} of ${mb(rt.maxMemory())} MB")
            appendLine("```")
            appendLine(trace.take(MaxTrace).trimEnd())
            appendLine("```")
        })
        files().drop(Kept).forEach { it.delete() }
    }

    /** The whole report, Markdown. Reads files and Android's exit records: call it off the main thread. */
    fun report(context: Context): String = buildString {
        val am = context.getSystemService(ActivityManager::class.java)
        val ram = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        appendLine("**Skipii ${BuildConfig.VERSION_NAME}** (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Build: ${Build.DISPLAY}")
        appendLine("Memory: app heap ${am.largeMemoryClass} MB (large) / ${am.memoryClass} MB, phone ${mb(ram.totalMem)} MB, ${mb(ram.availMem)} MB free now")
        appendLine("Settings: Shorts ${onOff(ShortsSetting.enabled)}, audio only ${onOff(AudioOnly.enabled)}, controls ${if (PlayerControls.inBar) "in the bar" else "on the video"}, signed in ${onOff(Account.signedIn)}")
        appendLine()
        appendLine("## Crashes")
        val saved = files()
        if (saved.isEmpty()) appendLine("None saved. (A native crash, low memory or the system closing Skipii leaves no trace here, only in the list below.)")
        saved.forEach { appendLine(); append(it.readText()) }
        appendLine()
        appendLine("## How Android ended Skipii")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) appendLine("Android 10 and older don't keep this.")
        else exits(am)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun StringBuilder.exits(am: ActivityManager) {
        val list = am.getHistoricalProcessExitReasons(BuildConfig.APPLICATION_ID, 0, Exits)
        if (list.isEmpty()) appendLine("No record.")
        list.forEach { e ->
            append("- ${stamp(e.timestamp)}: **${reasonName(e.reason)}** while ${importanceName(e.importance)}")
            append(" · status ${e.status} · pss ${e.pss / 1024} MB · rss ${e.rss / 1024} MB")
            e.description?.takeIf { it.isNotBlank() }?.let { append(" · \"$it\"") }
            appendLine()
            // An ANR's trace says where the main thread was stuck (a native crash's is a binary tombstone).
            if (e.reason == ApplicationExitInfo.REASON_ANR) e.traceInputStream?.use { s ->
                appendLine("  ```")
                appendLine(s.bufferedReader().readText().take(MaxTrace).trimEnd().prependIndent("  "))
                appendLine("  ```")
            }
        }
    }

    private fun reasonName(reason: Int) = when (reason) {
        1 -> "exited itself"
        2 -> "killed by a signal"
        3 -> "low memory"
        4 -> "crash"
        5 -> "native crash"
        6 -> "not responding (ANR)"
        7 -> "failed to start"
        8 -> "permission changed"
        9 -> "used too many resources"
        10 -> "closed by the user"
        11 -> "stopped by the user"
        12 -> "a dependency died"
        13 -> "other (the system)"
        14 -> "frozen"
        15 -> "package state changed"
        16 -> "app updated"
        else -> "unknown ($reason)"
    }

    private fun importanceName(importance: Int) = when {
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "playing in the background"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "a background service"
        else -> "cached in the background"
    }.let { "$it ($importance)" }

    private fun onOff(on: Boolean) = if (on) "on" else "off"
    private fun mb(bytes: Long) = bytes / (1024 * 1024)
    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(ms))

    private const val KEY_SHARED = "shared"
}
