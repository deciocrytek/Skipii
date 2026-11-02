package com.decio.skipii

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** One upload in a channel's feed. */
internal data class FeedEntry(val id: String, val title: String, val publishedMs: Long, val short: Boolean)

/**
 * New videos from the channels whose bell is on (Bells): a notification for each, tap = play it.
 * YouTube's own pushes only reach the YouTube app, so Skipii looks for itself: every 15 minutes
 * (WorkManager; Android stretches that when the phone sleeps) it reads each belled channel's
 * public RSS feed, no cookies, only as far as the first video it has already seen. A channel's
 * first check only notes what's there; videos from before the bell went on are never announced.
 * The worker also runs the daily bell sync (Bells.syncIfDue). Logs "new videos: …".
 */
object NewVideos {
    private const val TAG = "Skipii"
    private const val WORK = "new-videos"
    private const val CHANNEL = "new_videos"
    private const val KEEP_SEEN = 30
    /** How far into a feed to read when nothing in it has been seen (its newest few entries). */
    private const val MAX_FEED_BYTES = 96 * 1024L
    /** Older than this = a feed hiccup or a late find, not news. */
    private const val FRESH_MS = 3 * 24 * 60 * 60 * 1000L
    private const val MAX_PER_CHANNEL = 4

    private const val EXTRA_ID = "com.skipii.video.id"
    private const val EXTRA_TITLE = "com.skipii.video.title"
    private const val EXTRA_CHANNEL = "com.skipii.video.channel"
    private const val EXTRA_CHANNEL_ID = "com.skipii.video.channel_id"
    private const val EXTRA_AVATAR = "com.skipii.video.avatar"

    /** A video a notification asked to play; the shell opens it and clears this. */
    var opening by mutableStateOf<VideoItem?>(null)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NewVideoWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Notifications allowed for the app and not blocked for "New videos". */
    fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = ensureChannel(context) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Android's page for what [canNotify] found off: the app's notifications, or just "New videos". */
    fun openSettings(context: Context) {
        val appOff = !NotificationManagerCompat.from(context).areNotificationsEnabled()
        val intent = Intent(if (appOff) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .apply { if (!appOff) putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL) }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "new videos: no notification settings page (${it.message})") }
    }

    private fun ensureChannel(context: Context): NotificationChannel? {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        // Created again every time: that only renames an existing channel (to the app's current language),
        // the importance the person chose stays.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, str(R.string.app_new_videos_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = str(R.string.app_new_videos_channel_description)
            },
        )
        return manager.getNotificationChannel(CHANNEL)
    }

    /**
     * One pass over every belled channel, 4 at a time. YouTube's RSS feeds fail on and off (HTTP
     * 404 for hours at a time, widely reported since December 2025; 4 of 5 channels on
     * 2026-11-01), so the channels whose feed failed are filled in from the account's own
     * Subscriptions feed: one signed-in request, only in a pass where a feed failed.
     */
    suspend fun check(context: Context) {
        val channels = Bells.on.values.toList()
        if (channels.isEmpty()) return
        val prefs = context.getSharedPreferences("bells", Context.MODE_PRIVATE)
        val gate = Semaphore(4)
        val results = coroutineScope {
            channels.map { ch ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        ch to runCatching { checkChannel(context, prefs, ch) }
                            .onFailure { Log.w(TAG, "new videos: ${ch.id} feed failed (${it.message})") }
                            .getOrNull()
                    }
                }
            }.awaitAll()
        }
        var found = results.sumOf { it.second ?: 0 }
        val failed = results.filter { it.second == null }.map { it.first }
        if (failed.isNotEmpty() && Account.signedIn) {
            runCatching { fromSubscriptions(context, prefs, failed) }
                .onSuccess { found += it; Log.i(TAG, "new videos: ${failed.size} channels from the Subscriptions feed instead") }
                .onFailure { Log.w(TAG, "new videos: Subscriptions feed failed too (${it.message})") }
        }
        Log.i(TAG, "new videos: checked ${channels.size} channels, $found new")
    }

    private fun checkChannel(context: Context, prefs: android.content.SharedPreferences, ch: BellChannel): Int {
        val seen = prefs.getString("seen:${ch.id}", null)?.split(',')?.filter { it.isNotBlank() }
        val (feedName, entries) = fetchFeed(ch.id, seen.orEmpty().toHashSet())
        return record(context, prefs, ch, feedName, entries)
    }

    /** The failed channels' uploads from the first page of the account's Subscriptions feed (no Shorts there). */
    private suspend fun fromSubscriptions(context: Context, prefs: android.content.SharedPreferences, channels: List<BellChannel>): Int {
        val now = System.currentTimeMillis()
        val byChannel = InnerTube.subscriptionsFeed().videos.groupBy { it.channelUrl?.substringAfter("/channel/", "") }
        return channels.sumOf { ch ->
            val entries = byChannel[ch.id].orEmpty().mapNotNull { v ->
                ageMs(v.uploaded)?.let { FeedEntry(v.id, v.title, now - it, short = false) }
            }
            if (entries.isEmpty()) 0 else record(context, prefs, ch, byChannel[ch.id]?.firstOrNull()?.channel?.ifBlank { null }, entries)
        }
    }

    private val AGE = Regex("(\\d+)\\s*(second|minute|hour|day|week|month|year)s?\\s+ago")

    /** "3 hours ago", "Streamed 2 days ago" → how long ago in ms; null if it doesn't say. */
    internal fun ageMs(text: String?): Long? {
        val m = AGE.find(text?.lowercase() ?: return null) ?: return null
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2]) {
            "second" -> 1_000L
            "minute" -> 60_000L
            "hour" -> 3_600_000L
            "day" -> 86_400_000L
            "week" -> 7 * 86_400_000L
            "month" -> 30 * 86_400_000L
            else -> 365 * 86_400_000L
        }
        return n * unit
    }

    /**
     * Announces what's new in [entries] (newest first) and remembers them as seen. A channel's
     * first look only announces what went up after its bell did.
     */
    private fun record(context: Context, prefs: android.content.SharedPreferences, ch: BellChannel, feedName: String?, entries: List<FeedEntry>): Int {
        val key = "seen:${ch.id}"
        val stored = prefs.getString(key, null)
        val seen = stored?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        if (!Bells.isOn(ch.id)) return 0 // turned off meanwhile
        val now = System.currentTimeMillis()
        val fresh = entries.filter { it.id !in seen && now - it.publishedMs < FRESH_MS }
            // A channel's first look: only what went up after the bell did.
            .filter { stored != null || it.publishedMs > ch.since }
        val announce = fresh.filter { !it.short || ShortsSetting.enabled }.take(MAX_PER_CHANNEL).reversed()
        val name = ch.name ?: feedName ?: str(R.string.app_new_video_unnamed)
        announce.forEach { notify(context, ch, name, it) }
        val newSeen = (entries.map { it.id } + seen).distinct().take(KEEP_SEEN)
        if (newSeen != seen) prefs.edit().putString(key, newSeen.joinToString(",")).apply()
        return announce.size
    }

    /**
     * The feed's title and entries, newest first, read only up to the first entry in [seen] (all
     * of it, up to [MAX_FEED_BYTES], the first time). Closing early drops the rest unread.
     */
    private fun fetchFeed(channelId: String, seen: Set<String>): Pair<String?, List<FeedEntry>> {
        val request = Request.Builder()
            .url("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
            .header("User-Agent", Net.USER_AGENT)
            .build()
        Net.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("feed ${r.code}")
            val source = r.body?.source() ?: return null to emptyList()
            val bytes = okio.Buffer()
            val marks = seen.map { "<yt:videoId>$it</yt:videoId>".encodeUtf8() }
            while (bytes.size < MAX_FEED_BYTES && source.read(bytes, 8192) != -1L) {
                // Stop once an entry we already know has shown up: everything newer is above it.
                if (marks.any { bytes.indexOf(it) >= 0 }) break
            }
            return parseFeed(bytes.readUtf8())
        }
    }

    private val VIDEO_ID = Regex("<yt:videoId>([^<]+)</yt:videoId>")
    private val TITLE = Regex("<title>([^<]*)</title>")
    private val PUBLISHED = Regex("<published>([^<]+)</published>")

    /** A (possibly cut short) feed: its title and every entry complete enough to announce. */
    internal fun parseFeed(xml: String): Pair<String?, List<FeedEntry>> {
        val parts = xml.split("<entry>")
        val feedTitle = TITLE.find(parts.first())?.groupValues?.get(1)?.let(::unescape)?.trim()?.ifBlank { null }
        val entries = parts.drop(1).mapNotNull { e ->
            val id = VIDEO_ID.find(e)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val title = TITLE.find(e)?.groupValues?.get(1)?.let(::unescape)?.trim() ?: return@mapNotNull null
            val published = PUBLISHED.find(e)?.groupValues?.get(1)?.let { runCatching { OffsetDateTime.parse(it.trim()).toInstant().toEpochMilli() }.getOrNull() }
                ?: return@mapNotNull null
            FeedEntry(id, title, published, short = e.contains("youtube.com/shorts/"))
        }
        return feedTitle to entries
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    private fun notify(context: Context, ch: BellChannel, channelName: String, entry: FeedEntry) {
        if (!canNotify(context)) return
        val thumbnail = runCatching { fetchBitmap("https://i.ytimg.com/vi/${entry.id}/mqdefault.jpg") }.getOrNull()
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ID, entry.id)
            .putExtra(EXTRA_TITLE, entry.title)
            .putExtra(EXTRA_CHANNEL, channelName)
            .putExtra(EXTRA_CHANNEL_ID, ch.id)
            .putExtra(EXTRA_AVATAR, ch.avatar)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_skipii)
            .setContentTitle(channelName)
            .setContentText(entry.title)
            .setWhen(entry.publishedMs)
            .setShowWhen(true)
            .apply {
                if (thumbnail != null) {
                    setLargeIcon(thumbnail)
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumbnail).bigLargeIcon(null as Bitmap?))
                }
            }
            .setGroup(WORK)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, entry.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(entry.id.hashCode(), notification) }
            .onSuccess { Log.i(TAG, "new videos: ${entry.id} from ${ch.id} \"${entry.title}\"") }
            .onFailure { Log.w(TAG, "new videos: couldn't post ${entry.id} (${it.message})") }
    }

    private fun fetchBitmap(url: String): Bitmap? {
        val request = Request.Builder().url(url).header("User-Agent", Net.USER_AGENT).build()
        Net.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.byteStream()?.use(BitmapFactory::decodeStream)
        }
    }

    /** The video a notification's tap carries, or null. Clears it so a re-created window doesn't replay it. */
    fun takeVideo(intent: Intent?): VideoItem? {
        val id = intent?.getStringExtra(EXTRA_ID) ?: return null
        val item = VideoItem(
            id = id,
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty(),
            channelUrl = intent.getStringExtra(EXTRA_CHANNEL_ID)?.let { "https://www.youtube.com/channel/$it" },
            thumbnail = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            avatar = intent.getStringExtra(EXTRA_AVATAR),
            durationSec = 0, views = 0, uploaded = null,
        )
        intent.removeExtra(EXTRA_ID)
        return item
    }
}

/** The 15-minute pass: the daily bell sync (signed in), then the feeds. Signing out clears the bells. */
class NewVideoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Bells.syncIfDue() }.onFailure { Log.w("Skipii", "bells: sync failed (${it.message})") }
        withContext(Dispatchers.IO) { NewVideos.check(applicationContext) }
        return Result.success()
    }
}
