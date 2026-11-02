package com.decio.skipii

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** A channel with the bell on. [since]: when Skipii learned it (no notifications for videos before). */
data class BellChannel(val id: String, val name: String?, val avatar: String?, val since: Long = System.currentTimeMillis())

/**
 * The bell (Derek, 2026-11-01; mockup A in docs/design/bell.html): on = the account's "All" for that
 * channel, and Skipii posts a notification for each of its new videos (NewVideos). It's YouTube's own
 * setting: read wherever a page shows the channel's Subscribe button ([learn]) and from the whole
 * subscriptions list once a day ([syncIfDue]); written with notification/modify_channel_preference.
 * Off writes "Personalized", YouTube's default, so the YouTube app goes back to how it was.
 * [on] is the local copy the background check reads; it lives in prefs "bells".
 */
object Bells {
    private const val TAG = "Skipii"
    private const val KEY_ON = "on"
    private const val KEY_SYNCED = "synced_at"
    private const val SYNC_EVERY_MS = 20 * 60 * 60 * 1000L
    /** A page read this soon after a change may predate it: it doesn't overrule the change. */
    private const val WRITE_GUARD_MS = 30_000L

    private var prefs: SharedPreferences? = null
    private val scope = MainScope()
    private val writtenAt = HashMap<String, Long>()

    /** Channels with the bell on, by id. */
    val on = mutableStateMapOf<String, BellChannel>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bells", Context.MODE_PRIVATE)
        runCatching {
            val arr = JSONArray(prefs?.getString(KEY_ON, null) ?: return)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id").ifBlank { continue }
                on[id] = BellChannel(id, o.optString("name").ifBlank { null }, o.optString("avatar").ifBlank { null }, o.optLong("since"))
            }
        }.onFailure { Log.w(TAG, "bells: couldn't read the saved bells (${it.message})") }
    }

    fun isOn(channelId: String?): Boolean = channelId != null && on.containsKey(channelId)

    /**
     * What a YouTube page said about [channelId]: [subscribed] (false = no bell) and [bell]
     * (true = "All"; null = the page didn't show it, nothing changes). [name] / [avatar] refresh
     * the stored ones.
     */
    @Synchronized
    fun learn(channelId: String, subscribed: Boolean?, bell: Boolean?, name: String?, avatar: String?) {
        val written = writtenAt[channelId]
        if (written != null && SystemClock.elapsedRealtime() - written < WRITE_GUARD_MS) return
        val before = on[channelId]
        when {
            subscribed == false || bell == false -> if (before != null) { on.remove(channelId); save(); Log.i(TAG, "bells: off for $channelId (YouTube)") }
            bell == true -> {
                val now = BellChannel(channelId, name ?: before?.name, avatar ?: before?.avatar, before?.since ?: System.currentTimeMillis())
                if (now != before) { on[channelId] = now; save() }
                if (before == null) Log.i(TAG, "bells: on for $channelId (YouTube)")
            }
            before != null && (name != null || avatar != null) -> {
                val now = before.copy(name = name ?: before.name, avatar = avatar ?: before.avatar)
                if (now != before) { on[channelId] = now; save() }
            }
        }
    }

    /** Unsubscribed here: YouTube drops the bell with the subscription. */
    @Synchronized
    fun forget(channelId: String) {
        writtenAt[channelId] = SystemClock.elapsedRealtime()
        if (on.remove(channelId) != null) save()
    }

    @Synchronized
    fun clear() {
        on.clear()
        writtenAt.clear()
        prefs?.edit()?.clear()?.apply()
    }

    /** Turns [channel]'s bell on or off on the account; shows at once, undone if YouTube says no. */
    fun set(context: Context, channel: BellChannel, value: Boolean, onMessage: (String) -> Unit) {
        val name = channel.name
        setLocal(channel, value)
        scope.launch {
            runCatching { InnerTube.setBell(channel.id, value) }
                .onSuccess {
                    Log.i(TAG, "bells: ${if (value) "on" else "off"} for ${channel.id}")
                    onMessage(
                        if (name == null) str(if (value) R.string.app_bell_on_message_unnamed else R.string.app_bell_off_message_unnamed)
                        else str(if (value) R.string.app_bell_on_message else R.string.app_bell_off_message, name),
                    )
                    if (value) NewVideos.schedule(context)
                }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "bells: couldn't turn ${if (value) "on" else "off"} ${channel.id}", it)
                    setLocal(channel, !value)
                    onMessage(str(R.string.app_bell_failed))
                }
        }
    }

    @Synchronized
    private fun setLocal(channel: BellChannel, value: Boolean) {
        writtenAt[channel.id] = SystemClock.elapsedRealtime()
        if (value) on[channel.id] = on[channel.id] ?: channel.copy(since = System.currentTimeMillis()) else on.remove(channel.id)
        save()
    }

    /**
     * Reads every bell from the subscriptions list, at most once every 20 hours (bells changed in
     * the YouTube app show up here). Channels no longer subscribed lose theirs.
     */
    suspend fun syncIfDue(force: Boolean = false) {
        if (!Account.signedIn) return
        val last = prefs?.getLong(KEY_SYNCED, 0L) ?: return
        if (!force && System.currentTimeMillis() - last < SYNC_EVERY_MS) return
        val list = InnerTube.subscriptions() ?: return
        prefs?.edit()?.putLong(KEY_SYNCED, System.currentTimeMillis())?.apply()
        val withBell = list.count { it.bell != null }
        Log.i(TAG, "bells: synced ${list.size} subscriptions, $withBell with a bell state, ${list.count { it.bell == true }} on")
        if (list.isEmpty()) return
        list.forEach { learn(it.ref.id, true, it.bell, it.ref.name.ifBlank { null }, it.ref.avatar) }
        val subscribed = list.mapTo(HashSet()) { it.ref.id }
        on.keys.filter { it !in subscribed }.forEach { learn(it, subscribed = false, bell = null, name = null, avatar = null) }
    }

    private fun save() {
        val arr = JSONArray()
        on.values.forEach { ch ->
            arr.put(JSONObject().put("id", ch.id).put("name", ch.name).put("avatar", ch.avatar).put("since", ch.since))
        }
        prefs?.edit()?.putString(KEY_ON, arr.toString())?.apply()
    }
}

/**
 * The bell key's tap: turning a bell on needs notifications allowed (Android 13+ asks once; turned
 * off in Settings, Settings opens). Off always goes straight through.
 */
@Composable
fun rememberBellToggle(onMessage: (String) -> Unit): (BellChannel) -> Unit {
    val context = LocalContext.current
    var waiting by remember { mutableStateOf<BellChannel?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val channel = waiting ?: return@rememberLauncherForActivityResult
        waiting = null
        if (granted && NewVideos.canNotify(context)) Bells.set(context, channel, true, onMessage)
        else onMessage(str(R.string.app_bell_allow_notifications))
    }
    return { channel ->
        val turnOn = !Bells.isOn(channel.id)
        when {
            !turnOn || NewVideos.canNotify(context) -> Bells.set(context, channel, turnOn, onMessage)
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> {
                waiting = channel
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                onMessage(str(R.string.app_bell_turn_on_notifications))
                NewVideos.openSettings(context)
            }
        }
    }
}

/**
 * Subscribe, and once subscribed, the bell key joined to its right (mockup A): Subscribed (tonal,
 * inner corners 7 dp) · 3 dp · the key (48 dp, 64 when [wide]). Bell on = the key in full primary,
 * round, ringing. Subscribed still unsubscribes in one tap. [channel] null = not known yet: no key.
 * [wide] = the channel page's full-width button.
 */
@Composable
fun SubscribeWithBell(
    subscribed: Boolean,
    channel: BellChannel?,
    onSubscribe: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val toggleBell = rememberBellToggle(onMessage)
    val height = if (wide) 46.dp else 44.dp
    val outer = height / 2
    val keyWidth = if (wide) 64.dp else 48.dp
    val showKey = subscribed && channel != null
    val bell = showKey && Bells.isOn(channel?.id)
    val shape = if (reduced) tween<Dp>(0) else spring(dampingRatio = 0.6f, stiffness = 500f)
    val inner by animateDpAsState(if (showKey) 7.dp else outer, shape, label = "subInner")
    val keyStart by animateDpAsState(if (bell) outer else 7.dp, shape, label = "bellStart")
    val keyShown by animateFloatAsState(
        if (showKey) 1f else 0f,
        if (reduced) tween(0) else spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "bellKey",
    )
    val keyBg by animateColorAsState(if (bell) c.primary else c.secondaryContainer, tween(220), label = "bellBg")
    val keyInk by animateColorAsState(if (bell) c.onPrimary else c.onSecondaryContainer, tween(220), label = "bellInk")
    Row(modifier.height(height), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = (if (wide) Modifier.weight(1f) else Modifier)
                .squishOn(subscribed)
                .fillMaxHeight()
                .pressable(onClick = onSubscribe)
                .clip(RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner))
                .background(if (subscribed) c.secondaryContainer else c.onSurface)
                .padding(horizontal = if (subscribed) 16.dp else 20.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(if (subscribed) R.string.app_subscribed else R.string.app_subscribe),
                color = if (subscribed) c.onSecondaryContainer else c.surface,
                fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1,
            )
        }
        if (keyShown > 0f) {
            // Grows out of Subscribed's right edge: the gap and key take their width as it shows.
            Spacer(Modifier.width(3.dp * keyShown))
            Box(
                Modifier
                    .graphicsLayer { alpha = keyShown.coerceIn(0f, 1f); clip = true }
                    .layout { measurable, constraints ->
                        val w = (keyWidth.roundToPx() * keyShown).roundToInt().coerceAtLeast(0)
                        val p = measurable.measure(constraints.copy(minWidth = keyWidth.roundToPx(), maxWidth = keyWidth.roundToPx()))
                        layout(w, p.height) { p.place(0, 0) }
                    }
                    .fillMaxHeight()
                    .width(keyWidth)
                    .squishOn(bell)
                    .pressable(enabled = showKey) { channel?.let(toggleBell) }
                    .clip(RoundedCornerShape(topStart = keyStart, bottomStart = keyStart, topEnd = outer, bottomEnd = outer))
                    .background(keyBg),
                contentAlignment = Alignment.Center,
            ) {
                FlourishIcon(
                    if (bell) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                    stringResource(if (bell) R.string.app_bell_on_description else R.string.app_bell_off_description),
                    active = bell, kind = Flourish.Ring, tint = keyInk, size = 21.dp,
                )
            }
        }
    }
}
