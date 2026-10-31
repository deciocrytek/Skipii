package com.decio.skipii

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Self-update from the GitHub releases (issue #13). Once a day the newest release's Skipii.apk is
 * looked up; when it's newer than this build, Home shows an Update button next to the profile
 * picture. Tapping it downloads the APK (checked against GitHub's size and sha256) and hands it to
 * PackageInstaller. The first update asks the user (and Android may first ask to allow installs from
 * Skipii); after that Skipii is its own installer and Android 12+ updates it without a prompt, then a
 * notification says it's done. Nothing newer = nothing shown. On Wi-Fi the APK is fetched ahead, so
 * the tap goes straight to installing. Logs "update check: …" / "update: …".
 *
 * What's new (issue #30): the button opens a sheet with the notes of every release newer than this
 * build (scripts/publish-apk.sh posts each version's CHANGELOG.md section as its release notes), and
 * the sheet's own Update button installs.
 */
object AppUpdate {
    private const val TAG = "Skipii"
    /** Newest first; enough to cover the notes of every release a phone may have skipped. */
    private const val RELEASES_URL = "https://api.github.com/repos/deciocrytek/Skipii/releases?per_page=30"
    private const val APK_ASSET = "Skipii.apk"
    private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L
    /** A check that failed (offline, rate limit) tries again after this, not a day later. */
    private const val RETRY_AFTER_MS = 60 * 60 * 1000L
    private const val KEY_NEXT_CHECK = "next_check"
    private const val KEY_RELEASE = "release"
    /**
     * The version that made the last check. A different one (just installed from GitHub, Drive or
     * adb) checks at once: installing over the app keeps the daily timer, and an install from
     * Drive showed no Update button until the next day (2026-10-29).
     */
    private const val KEY_CHECKED_BY = "checked_by"
    /** The version an install was started for: the next start of that version posts "updated". */
    private const val KEY_INSTALLING = "installing"
    /** The install asked the user; Android's own dialog then offers Open, so no notification. */
    private const val KEY_PROMPTED = "prompted"
    private const val CHANNEL = "updates"
    internal const val ACTION_RESULT = "com.skipii.UPDATE_RESULT"

    data class Release(
        val version: String,
        val apkUrl: String,
        val size: Long,
        val sha256: String?,
        /** Newest first: this release's notes and those of the releases between it and this build. */
        val notes: List<ReleaseNotes> = emptyList(),
    )

    /** One release's notes as published (markdown); [sections] is what the sheet shows. */
    data class ReleaseNotes(val version: String, val body: String) {
        val sections by lazy { parseNotes(body) }
    }

    /** "New" / "Fixed" / … (null before any heading) and its bullet points. */
    data class NoteSection(val title: String?, val items: List<String>)

    enum class Phase { Ready, Downloading, Installing, Failed }

    /** The newer release, or null when this build is the newest (then nothing is shown). */
    var available by mutableStateOf<Release?>(null)
        private set
    var phase by mutableStateOf(Phase.Ready)
        private set
    /** Download progress 0…1 (written from the download thread). */
    var progress by mutableFloatStateOf(0f)
        private set
    /** The button pops in only the first time it shows in this process. */
    internal var announced = false

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = MainScope()
    private var checking = false
    private var download: Deferred<File?>? = null
    private var downloadVersion: String? = null
    /** Sent to "Install unknown apps"; the install carries on when the window comes back. */
    private var awaitingPermission = false

    fun init(context: Context) {
        app = context.applicationContext
        prefs = app.getSharedPreferences("update", Context.MODE_PRIVATE)
        // Shown straight away from the last check; the daily check corrects it.
        available = savedRelease()?.takeIf { isNewer(it.version) }
        // An installed update leaves its APK behind; keep only the one still to install.
        val keep = available?.let { apkFile(it).name }
        dir().listFiles()?.forEach { if (it.name != keep) it.delete() }
    }

    /** Called whenever a window comes to the front; looks GitHub up at most once a day, and on a new version's first start. */
    fun checkIfDue() {
        val newInstall = prefs.getString(KEY_CHECKED_BY, null) != BuildConfig.VERSION_NAME
        if (checking || (!newInstall && System.currentTimeMillis() < prefs.getLong(KEY_NEXT_CHECK, 0L))) return
        checking = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { fetchLatest() } }
            checking = false
            val now = System.currentTimeMillis()
            result.onFailure {
                Log.w(TAG, "update check failed (${it.message}); trying again in an hour")
                prefs.edit().putLong(KEY_NEXT_CHECK, now + RETRY_AFTER_MS).putString(KEY_CHECKED_BY, BuildConfig.VERSION_NAME).apply()
                return@launch
            }
            val latest = result.getOrNull()
            prefs.edit().putLong(KEY_NEXT_CHECK, now + CHECK_EVERY_MS).putString(KEY_CHECKED_BY, BuildConfig.VERSION_NAME)
                .apply { if (latest != null) putString(KEY_RELEASE, latest.toJson()) else remove(KEY_RELEASE) }
                .apply()
            val newer = latest?.takeIf { isNewer(it.version) }
            Log.i(TAG, "update check: latest ${latest?.version ?: "none"}, this ${BuildConfig.VERSION_NAME}" +
                if (newer != null) " → update available" else "")
            if (phase == Phase.Downloading || phase == Phase.Installing) return@launch
            if (newer != available) { available = newer; phase = Phase.Ready }
            if (newer != null && !metered()) apkFor(newer)
        }
    }

    /** The What's new sheet's Update button. */
    fun install(context: Context) {
        val release = available ?: return
        if (phase == Phase.Downloading || phase == Phase.Installing) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            awaitingPermission = true
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        phase = Phase.Downloading
        scope.launch {
            val apk = apkFor(release).await()
            if (apk == null) { phase = Phase.Failed; return@launch }
            phase = Phase.Installing
            runCatching { withContext(Dispatchers.IO) { commit(apk, release) } }.onFailure {
                Log.w(TAG, "update: couldn't start the install", it)
                phase = Phase.Failed
            }
        }
    }

    /** Back from "Install unknown apps": install if it was allowed, else the button waits again. */
    fun onWindowResumed(context: Context) {
        if (!awaitingPermission) return
        awaitingPermission = false
        if (app.packageManager.canRequestPackageInstalls()) install(context)
    }

    /** PackageInstaller's answer to [commit] (via [UpdateReceiver]). */
    internal fun onInstallResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) { phase = Phase.Failed; return }
                prefs.edit().putBoolean(KEY_PROMPTED, true).apply()
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // this process is replaced; onUpdated runs in the new one
            PackageInstaller.STATUS_FAILURE_ABORTED -> { // the user said no
                prefs.edit().remove(KEY_INSTALLING).remove(KEY_PROMPTED).apply()
                phase = Phase.Ready
            }
            else -> {
                Log.w(TAG, "update: install failed ($status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)})")
                prefs.edit().remove(KEY_INSTALLING).remove(KEY_PROMPTED).apply()
                phase = Phase.Failed
            }
        }
    }

    /** The new version's first start after [commit] installed it. */
    internal fun onUpdated(context: Context) {
        val version = prefs.getString(KEY_INSTALLING, null) ?: return
        val prompted = prefs.getBoolean(KEY_PROMPTED, false)
        prefs.edit().remove(KEY_INSTALLING).remove(KEY_PROMPTED).apply()
        Log.i(TAG, "update: now on ${BuildConfig.VERSION_NAME} (installed $version)")
        // A silent update closed the app with no word; Android's own dialog already offered Open.
        if (prompted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Skipii updated to ${BuildConfig.VERSION_NAME}")
            .setContentText("Tap to open")
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(1, notification) }
            .onFailure { Log.w(TAG, "update: couldn't post the updated notification (${it.message})") }
    }

    private fun fetchLatest(): Release? {
        val request = Request.Builder().url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Skipii/${BuildConfig.VERSION_NAME}")
            .build()
        Net.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            return parseReleases(JSONArray(response.body?.string().orEmpty()), BuildConfig.VERSION_NAME)
        }
    }

    /**
     * The releases list → the newest published release with a Skipii.apk, carrying the notes of every
     * release newer than [current] up to it (a phone that skipped versions sees all of their changes).
     */
    internal fun parseReleases(json: JSONArray, current: String): Release? {
        val published = (0 until json.length()).map { json.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
        val latest = published.mapNotNull { parseRelease(it) }.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
            ?: return null
        val notes = published
            .map { ReleaseNotes(it.optString("tag_name").removePrefix("v"), it.optString("body")) }
            .filter { compareVersions(it.version, current) > 0 && compareVersions(it.version, latest.version) <= 0 }
            .filter { it.sections.isNotEmpty() }
            .sortedWith { a, b -> compareVersions(b.version, a.version) }
        return latest.copy(notes = notes)
    }

    /** The latest-release JSON → its Skipii.apk, or null when it has none. */
    internal fun parseRelease(json: JSONObject): Release? {
        val version = json.optString("tag_name").removePrefix("v").ifBlank { return null }
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == APK_ASSET } ?: return null
        return Release(
            version = version,
            apkUrl = apk.optString("browser_download_url").ifBlank { return null },
            size = apk.optLong("size"),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            notes = listOf(ReleaseNotes(version, json.optString("body"))).filter { it.sections.isNotEmpty() },
        )
    }

    /**
     * A release body → its sections: "### New" headings and "- " bullets (a bullet's indented next lines
     * join it). Everything after a "---" line is for the web page (install help), and so is any loose
     * paragraph; bold, code and links keep only their text.
     */
    internal fun parseNotes(body: String): List<NoteSection> {
        val sections = mutableListOf<NoteSection>()
        var title: String? = null
        val items = mutableListOf<String>()
        fun flush() {
            if (items.isNotEmpty()) sections += NoteSection(title, items.toList())
            items.clear()
        }
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line == "---") break
            when {
                line.startsWith("#") -> { flush(); title = plain(line.trimStart('#').trim()).ifBlank { null } }
                line.startsWith("- ") || line.startsWith("* ") -> items += plain(line.substring(2).trim())
                line.isNotEmpty() && items.isNotEmpty() && raw.startsWith("  ") -> items[items.lastIndex] += " " + plain(line)
            }
        }
        flush()
        return sections
    }

    private fun plain(markdown: String) = markdown
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("""`([^`]+)`"""), "$1")

    /** "0.5.55" vs "0.5.54" → positive; missing parts count as 0. */
    internal fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun isNewer(version: String) = compareVersions(version, BuildConfig.VERSION_NAME) > 0

    private fun metered() = app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false

    /** The APK on disk, downloading it once; a tap during a Wi-Fi prefetch waits for that one. */
    private fun apkFor(release: Release): Deferred<File?> {
        download?.takeIf { it.isActive && downloadVersion == release.version }?.let { return it }
        downloadVersion = release.version
        return scope.async(Dispatchers.IO) {
            runCatching { downloadApk(release) }
                .onFailure { Log.w(TAG, "update: download of ${release.version} failed (${it.message})") }
                .getOrNull()
        }.also { download = it }
    }

    private fun downloadApk(release: Release): File {
        val file = apkFile(release)
        if (file.length() == release.size && (release.sha256 == null || file.sha256() == release.sha256)) {
            progress = 1f
            return file
        }
        progress = 0f
        val part = File(dir(), file.name + ".part")
        val request = Request.Builder().url(release.apkUrl).header("User-Agent", "Skipii/${BuildConfig.VERSION_NAME}").build()
        Net.client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "no body" }
            val total = release.size.takeIf { it > 0 } ?: body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            part.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (total > 0) progress = (read.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
            check(release.size <= 0 || read == release.size) { "got $read bytes, expected ${release.size}" }
            release.sha256?.let { check(digest.digest().hex() == it) { "sha256 mismatch" } }
        }
        check(part.renameTo(file)) { "couldn't rename ${part.name}" }
        Log.i(TAG, "update: downloaded ${release.version} (${file.length()} bytes)")
        return file
    }

    private fun commit(apk: File, release: Release) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Once Skipii installed itself, Android 12+ lets it update without asking.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            session.openWrite(APK_ASSET, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            prefs.edit().putString(KEY_INSTALLING, release.version).putBoolean(KEY_PROMPTED, false).commit()
            val result = Intent(app, UpdateReceiver::class.java).setAction(ACTION_RESULT)
            // Mutable: PackageInstaller fills in the status extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            Log.i(TAG, "update: installing ${release.version}")
            session.commit(PendingIntent.getBroadcast(app, id, result, flags).intentSender)
            session.close()
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    private fun dir() = File(app.cacheDir, "update").apply { mkdirs() }
    private fun apkFile(release: Release) = File(dir(), "Skipii-${release.version}.apk")

    private fun savedRelease(): Release? = prefs.getString(KEY_RELEASE, null)?.let {
        runCatching {
            val o = JSONObject(it)
            val notes = o.optJSONArray("notes") ?: JSONArray()
            Release(
                o.getString("version"), o.getString("url"), o.getLong("size"), o.optString("sha256").ifBlank { null },
                (0 until notes.length()).map { notes.getJSONObject(it).let { n -> ReleaseNotes(n.getString("version"), n.getString("body")) } },
            )
        }.getOrNull()
    }

    private fun Release.toJson() =
        JSONObject().put("version", version).put("url", apkUrl).put("size", size).put("sha256", sha256 ?: "")
            .put("notes", JSONArray(notes.map { JSONObject().put("version", it.version).put("body", it.body) }))
            .toString()

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

/** PackageInstaller results, and the first start of a freshly installed version. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AppUpdate.ACTION_RESULT -> AppUpdate.onInstallResult(context, intent)
            Intent.ACTION_MY_PACKAGE_REPLACED -> AppUpdate.onUpdated(context)
        }
    }
}

/**
 * The header's Update pill, beside the profile picture; absent when there's nothing newer. A tap opens
 * [WhatsNewSheet]. While downloading it fills left to right with the percentage; "Retry" after a failure.
 */
@Composable
fun UpdateAction() {
    val release = AppUpdate.available ?: return
    val c = MaterialTheme.colorScheme
    val phase = AppUpdate.phase
    var sheet by remember { mutableStateOf(false) }
    val appear = remember { Animatable(if (AppUpdate.announced) 1f else 0f) }
    LaunchedEffect(Unit) {
        AppUpdate.announced = true
        appear.animateTo(1f, tween(420, easing = Motion.Pop))
    }
    Box(
        modifier = Modifier
            .graphicsLayer {
                val v = appear.value
                alpha = v.coerceIn(0f, 1f)
                scaleX = 0.6f + 0.4f * v
                scaleY = 0.6f + 0.4f * v
            }
            .pressable { sheet = true }
            .height(44.dp)
            .widthIn(min = 104.dp)
            .clip(CircleShape)
            .background(c.primary)
            .drawBehind {
                if (phase == AppUpdate.Phase.Downloading) {
                    drawRect(c.onPrimary.copy(alpha = 0.22f), size = Size(size.width * AppUpdate.progress, size.height))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (phase == AppUpdate.Phase.Failed) Icons.Rounded.Refresh else Icons.Rounded.SystemUpdateAlt,
                contentDescription = null,
                tint = c.onPrimary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                when (phase) {
                    AppUpdate.Phase.Ready -> "Update"
                    AppUpdate.Phase.Downloading -> "${(AppUpdate.progress * 100).toInt()}%"
                    AppUpdate.Phase.Installing -> "Installing"
                    AppUpdate.Phase.Failed -> "Retry"
                },
                color = c.onPrimary,
                fontFamily = RoundedHeavy,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                maxLines = 1,
            )
        }
    }
    if (sheet) WhatsNewSheet(release, onDismiss = { sheet = false })
}

/**
 * What's new (issue #30): the notes of every release since this build, newest first (each under its
 * version when there are several), and the Update button under them, pinned while the notes scroll.
 * The button fills with the download and stays here through Installing; a silent update just closes
 * the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsNewSheet(release: AppUpdate.Release, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val phase = AppUpdate.phase
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Text(
            "What's new",
            fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            listOfNotNull("Skipii ${release.version}", release.size.takeIf { it > 0 }?.let { "%.1f MB".format(Locale.US, it / 1_048_576.0) }).joinDots(),
            color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 14.dp),
        )
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (release.notes.isEmpty()) {
                Text("No notes were published with this version.", color = c.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp)
            }
            val several = release.notes.size > 1
            release.notes.forEach { notes ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (several) {
                        Text(notes.version, color = c.primary, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                    notes.sections.forEach { NoteSectionBlock(it) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .pressable(enabled = phase == AppUpdate.Phase.Ready || phase == AppUpdate.Phase.Failed) { AppUpdate.install(context) }
                .height(54.dp)
                .clip(CircleShape)
                .background(c.primary)
                .drawBehind {
                    if (phase == AppUpdate.Phase.Downloading) {
                        drawRect(c.onPrimary.copy(alpha = 0.22f), size = Size(size.width * AppUpdate.progress, size.height))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (phase) {
                    AppUpdate.Phase.Ready -> "Update"
                    AppUpdate.Phase.Downloading -> "Downloading ${(AppUpdate.progress * 100).toInt()}%"
                    AppUpdate.Phase.Installing -> "Installing…"
                    AppUpdate.Phase.Failed -> "Try again"
                },
                color = c.onPrimary,
                fontFamily = RoundedHeavy,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NoteSectionBlock(section: AppUpdate.NoteSection) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        section.title?.let { Text(it, color = c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        section.items.forEach { item ->
            Row {
                // The dot sits on the first line's middle (15 sp text on 21 sp lines).
                Box(Modifier.padding(top = 8.dp, end = 12.dp).size(5.dp).clip(CircleShape).background(c.primary))
                Text(item, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}
