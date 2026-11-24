package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.FilterListOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** A channel's id from a card's channel link ("https://www.youtube.com/channel/UC…"), or null. */
internal fun VideoItem.channelId(): String? = channelUrl?.substringAfter("/channel/", "")?.takeIf { it.startsWith("UC") }?.substringBefore('/')

/** Case and accents don't matter ("Reação" is muted by "reacao"). */
internal fun foldWords(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/**
 * "Your feed" (0.6.0; Derek approved section 2 of docs/design/skipii-2.html without DeArrow): rules Skipii applies
 * itself to Home and Subscriptions — never to History, playlists, search or channel pages. Kept on the phone
 * (prefs "feed"); the account isn't told.
 */
object FeedFilters {
    private var prefs: SharedPreferences? = null
    var hideWatched by mutableStateOf(false)
        private set
    /** Videos shorter than this many seconds are hidden; 0 = off. A video of unknown length always shows. */
    var minSeconds by mutableIntStateOf(0)
        private set
    var mutedWords by mutableStateOf<List<String>>(emptyList())
        private set
    var mutedChannels by mutableStateOf<List<ChannelRef>>(emptyList())
        private set
    /** GitHub #96: the subscriptions' Shorts as a row on Subscriptions and Home, even with the Shorts tab off. */
    var subscriptionShorts by mutableStateOf(false)
        private set
    val minChoices = listOf(0, 60, 180, 600)

    fun init(context: Context) {
        val p = context.getSharedPreferences("feed", Context.MODE_PRIVATE).also { prefs = it }
        hideWatched = p.getBoolean("hide_watched", false)
        minSeconds = p.getInt("min_seconds", 0).takeIf { it in minChoices } ?: 0
        mutedWords = p.getString("muted_words", null)?.let { s -> runCatching { JSONArray(s).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull() }.orEmpty()
        mutedChannels = p.getString("muted_channels", null)?.let { s ->
            runCatching { JSONArray(s).let { a -> List(a.length()) { a.getJSONObject(it).let { o -> ChannelRef(o.getString("id"), o.optString("name"), o.optString("avatar").ifBlank { null }) } } } }.getOrNull()
        }.orEmpty()
        subscriptionShorts = p.getBoolean("subscription_shorts", false)
    }

    /** Any rule is on (feeds page on past pages that show nothing new, [FeedList] `filtered`). */
    val active get() = hideWatched || minSeconds > 0 || mutedWords.isNotEmpty() || mutedChannels.isNotEmpty()

    fun chooseHideWatched(on: Boolean) { hideWatched = on; prefs?.edit()?.putBoolean("hide_watched", on)?.apply() }
    fun chooseMinSeconds(s: Int) { minSeconds = s; prefs?.edit()?.putInt("min_seconds", s)?.apply() }
    fun chooseSubscriptionShorts(on: Boolean) { subscriptionShorts = on; prefs?.edit()?.putBoolean("subscription_shorts", on)?.apply() }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.isEmpty() || mutedWords.any { foldWords(it) == foldWords(w) }) return
        mutedWords = listOf(w) + mutedWords
        prefs?.edit()?.putString("muted_words", JSONArray(mutedWords).toString())?.apply()
    }

    fun removeWord(word: String) {
        mutedWords = mutedWords - word
        prefs?.edit()?.putString("muted_words", JSONArray(mutedWords).toString())?.apply()
    }

    /** Mutes [video]'s channel (a card's long-press). Returns the muted channel, for the Undo; null if the card has none. */
    fun muteChannelOf(video: VideoItem): ChannelRef? {
        val id = video.channelId() ?: return null
        if (mutedChannels.any { it.id == id }) return null
        val ref = ChannelRef(id, video.channel, video.avatar)
        mutedChannels = listOf(ref) + mutedChannels
        writeChannels()
        return ref
    }

    fun unmute(channel: ChannelRef) {
        mutedChannels = mutedChannels.filterNot { it.id == channel.id }
        writeChannels()
    }

    private fun writeChannels() {
        val a = JSONArray()
        mutedChannels.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar ?: "")) }
        prefs?.edit()?.putString("muted_channels", a.toString())?.apply()
    }

    fun hides(v: VideoItem): Boolean {
        if (hideWatched && WatchedStore.watched(v.id)) return true
        if (minSeconds > 0 && v.durationSec in 1 until minSeconds) return true
        if (mutedChannels.isNotEmpty()) {
            val id = v.channelId()
            if (mutedChannels.any { (id != null && it.id == id) || (id == null && it.name.isNotBlank() && it.name == v.channel) }) return true
        }
        if (mutedWords.isNotEmpty()) {
            val title = foldWords(v.title)
            if (mutedWords.any { title.contains(foldWords(it)) }) return true
        }
        return false
    }

    fun apply(videos: List<VideoItem>): List<VideoItem> = if (!active) videos else videos.filterNot(::hides)
}

/** A group of subscribed channels, made on the phone (Subscriptions' chips). */
@Immutable
data class SubscriptionGroup(val id: String, val name: String, val channels: Set<String>)

/**
 * Subscription groups and "Unwatched only" (0.6.0; section 7 of docs/design/skipii-2.html). Local only
 * (prefs "sub_groups"): nothing changes on the account. [selected] is this run's pick.
 */
object SubscriptionGroups {
    private var prefs: SharedPreferences? = null
    var groups by mutableStateOf<List<SubscriptionGroup>>(emptyList())
        private set
    var selected by mutableStateOf<String?>(null)
    var unwatchedOnly by mutableStateOf(false)
        private set

    fun init(context: Context) {
        val p = context.getSharedPreferences("sub_groups", Context.MODE_PRIVATE).also { prefs = it }
        groups = p.getString("groups", null)?.let { s ->
            runCatching {
                JSONArray(s).let { a ->
                    List(a.length()) { i ->
                        val o = a.getJSONObject(i)
                        val ids = o.getJSONArray("channels")
                        SubscriptionGroup(o.getString("id"), o.getString("name"), List(ids.length()) { ids.getString(it) }.toSet())
                    }
                }
            }.getOrNull()
        }.orEmpty()
        unwatchedOnly = p.getBoolean("unwatched_only", false)
    }

    val current: SubscriptionGroup? get() = groups.firstOrNull { it.id == selected }

    fun chooseUnwatchedOnly(on: Boolean) { unwatchedOnly = on; prefs?.edit()?.putBoolean("unwatched_only", on)?.apply() }

    /** Adds or replaces (same id) a group. */
    fun save(group: SubscriptionGroup) {
        groups = if (groups.any { it.id == group.id }) groups.map { if (it.id == group.id) group else it } else groups + group
        write()
    }

    fun delete(id: String) {
        groups = groups.filterNot { it.id == id }
        if (selected == id) selected = null
        write()
    }

    private fun write() {
        val a = JSONArray()
        groups.forEach { g -> a.put(JSONObject().put("id", g.id).put("name", g.name).put("channels", JSONArray(g.channels.toList()))) }
        prefs?.edit()?.putString("groups", a.toString())?.apply()
    }

    /** What Subscriptions shows under the chosen group and "Unwatched only". */
    fun apply(videos: List<VideoItem>): List<VideoItem> {
        val g = current
        return videos.filter { v ->
            (g == null || v.channelId()?.let { it in g.channels } == true) && (!unwatchedOnly || !WatchedStore.watched(v.id))
        }
    }

    val narrowing get() = current != null || unwatchedOnly
}

// ── UI ─────────────────────────────────────────────────────────────────────────

/** Sheet's section label: primary, bold (as the Settings group labels). */
@Composable
internal fun SheetLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 12.5.sp,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** A title and a line with a switch; the whole row toggles. On an RTL-language phone the switch sits left (as Settings). */
@Composable
internal fun SheetSwitch(title: String, line: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    val rtl = rememberRtlLanguage()
    val toggle = @Composable {
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.primary))
        }
    }
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rtl) { toggle(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
            if (line != null) {
                Spacer(Modifier.height(2.dp))
                Text(line, color = c.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
        if (!rtl) { Spacer(Modifier.width(12.dp)); toggle() }
    }
}

/** A removable chip: the word (or a channel with its avatar) and a ✕; primary container. */
@Composable
internal fun RemovableChip(label: String, avatar: String? = null, description: String, onRemove: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.primaryContainer).padding(start = if (avatar != null) 6.dp else 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatar != null) { ChannelAvatar(avatar, label, size = 24); Spacer(Modifier.width(7.dp)) }
        Text(label, color = c.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        Box(Modifier.size(28.dp).pressable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, description, tint = c.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
    }
}

/** A tonal "+ label" pill (adds something). */
@Composable
internal fun AddPill(label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.height(36.dp).pressable(onClick = onClick).clip(RoundedCornerShape(18.dp)).background(c.surfaceContainerHigh).padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = c.onSurface, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = c.onSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * "Your feed" (Neon's spec): hide watched, hide shorter than, muted words (inline add), muted channels (added from
 * a card's long-press), and the subscriptions' Shorts row (#96). Every change applies at once; [hidden] says how
 * many loaded videos the rules hide right now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedFiltersSheet(hidden: Int, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            Text(
                stringResource(R.string.feed_title), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                if (hidden > 0) pluralStringResource(R.plurals.feed_hiding, hidden, hidden.toString()) else stringResource(R.string.feed_hiding_none),
                color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
            )
            SheetSwitch(stringResource(R.string.feed_hide_watched), stringResource(R.string.feed_hide_watched_line), FeedFilters.hideWatched) { FeedFilters.chooseHideWatched(it) }
            SheetLabel(stringResource(R.string.feed_shorter_than))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FeedFilters.minChoices) { s ->
                    FilterPill(
                        if (s == 0) stringResource(R.string.feed_off) else stringResource(R.string.feed_minutes, (s / 60).toString()),
                        selected = FeedFilters.minSeconds == s,
                    ) { FeedFilters.chooseMinSeconds(s) }
                }
            }
            SheetLabel(stringResource(R.string.feed_muted_words))
            MutedWords()
            SheetLabel(stringResource(R.string.feed_muted_channels))
            if (FeedFilters.mutedChannels.isEmpty()) {
                Text(
                    stringResource(R.string.feed_muted_channels_none), color = c.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FeedFilters.mutedChannels, key = { it.id }) { ch ->
                        Box(Modifier.animateItem()) {
                            RemovableChip(ch.name, ch.avatar, stringResource(R.string.feed_unmute, ch.name)) { FeedFilters.unmute(ch) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SheetSwitch(stringResource(R.string.feed_subscription_shorts), stringResource(R.string.feed_subscription_shorts_line), FeedFilters.subscriptionShorts) {
                FeedFilters.chooseSubscriptionShorts(it)
            }
        }
    }
}

/** Muted words: "+ Add" first (always in reach), then the words; Add opens a field in its place (Done adds). */
@Composable
private fun MutedWords() {
    val c = MaterialTheme.colorScheme
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val view = LocalView.current
    fun commit() {
        FeedFilters.addWord(draft)
        draft = ""
        adding = false
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        // Rule 4: the window-level hide after the IME's own handling.
        view.postDelayed({ view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0) }, 120)
    }
    val reduced = rememberReducedMotion()
    Column(Modifier.animateContentSize(if (reduced) tween(0) else spring(dampingRatio = 0.85f, stiffness = 480f))) {
        if (adding) {
            val shown by animateFloatAsState(1f, label = "wordField")
            Row(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp).fillMaxWidth().height(46.dp).graphicsLayer { alpha = shown }
                    .clip(CircleShape).background(c.surfaceContainerHigh).padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (draft.isEmpty()) Text(stringResource(R.string.feed_word_hint), color = c.onSurfaceVariant, fontSize = 15.sp)
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = OwnDirectionText.merge(TextStyle(color = c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)),
                        cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Box(Modifier.size(36.dp).pressable { commit() }.clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, stringResource(R.string.feed_add_word), tint = c.onPrimary, modifier = Modifier.size(18.dp))
                }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { focus.focusWhenReady() }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!adding) item(key = "add") { Box(Modifier.animateItem()) { AddPill(stringResource(R.string.feed_add)) { adding = true } } }
            items(FeedFilters.mutedWords, key = { "w-$it" }) { w ->
                Box(Modifier.animateItem()) { RemovableChip(w, null, stringResource(R.string.feed_unmute, w)) { FeedFilters.removeWord(w) } }
            }
        }
    }
}

/**
 * A subscription group, new ([group] null) or edited: its name and a checklist of the subscribed channels (a search
 * over them once there are many). Save needs a name and a channel; editing also offers Delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditorSheet(group: SubscriptionGroup?, channels: List<ChannelRef>, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(group?.name.orEmpty()) }
    var picked by remember { mutableStateOf(group?.channels.orEmpty()) }
    var query by remember { mutableStateOf("") }
    val canSave = name.isNotBlank() && picked.isNotEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        // Read INSIDE the sheet: it's its own window, and the page's keyboard / view can't hide this one's (0.5.127).
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val view = LocalView.current
        fun hideKeyboard() {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            view.postDelayed({ view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0) }, 120)
        }
        Column(Modifier.padding(bottom = 20.dp)) {
            Text(
                stringResource(if (group == null) R.string.feed_group_new else R.string.feed_group_edit),
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
            SheetField(name, { name = it }, stringResource(R.string.feed_group_name_hint), onDone = ::hideKeyboard)
            if (channels.size > 12) {
                Spacer(Modifier.height(8.dp))
                SheetField(query, { query = it }, stringResource(R.string.feed_group_search_hint), icon = true, onDone = ::hideKeyboard)
            }
            SheetLabel(pluralStringResource(R.plurals.feed_group_picked, picked.size, picked.size.toString()))
            val q = foldWords(query.trim())
            val shown = if (q.isEmpty()) channels else channels.filter { foldWords(it.name).contains(q) }
            if (channels.isEmpty()) {
                Text(stringResource(R.string.feed_group_no_channels), color = c.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(shown, key = { it.id }) { ch ->
                    val on = ch.id in picked
                    Row(
                        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { picked = if (on) picked - ch.id else picked + ch.id }
                            .padding(horizontal = 20.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelAvatar(ch.avatar, ch.name, size = 40)
                        Spacer(Modifier.width(14.dp))
                        Text(ch.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        CheckCircle(on)
                    }
                }
            }
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (group != null) {
                    Box(
                        Modifier.height(48.dp).pressable { SubscriptionGroups.delete(group.id); onDismiss() }.clip(CircleShape)
                            .background(c.errorContainer).padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.feed_group_delete), color = c.onErrorContainer, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
                }
                Box(
                    Modifier.weight(1f).height(48.dp)
                        .pressable(enabled = canSave) {
                            val id = group?.id ?: ("g" + System.currentTimeMillis())
                            SubscriptionGroups.save(SubscriptionGroup(id, name.trim(), picked))
                            SubscriptionGroups.selected = id
                            onDismiss()
                        }
                        .graphicsLayer { alpha = if (canSave) 1f else 0.4f }
                        .clip(CircleShape).background(c.primary),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.feed_group_save), color = c.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
            }
        }
    }
}

/** A round check that fills primary when on (Save to playlist's look). */
@Composable
internal fun CheckCircle(on: Boolean) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val fill by animateFloatAsState(if (on) 1f else 0f, if (reduced) tween(0) else if (on) spring(dampingRatio = 0.45f, stiffness = 650f) else tween(140, easing = Motion.Exit), label = "check")
    Box(Modifier.size(26.dp).clip(CircleShape).background(c.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Box(Modifier.size(26.dp).graphicsLayer { scaleX = fill; scaleY = fill; alpha = fill.coerceIn(0f, 1f) }.clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = c.onPrimary, modifier = Modifier.size(17.dp))
        }
    }
}

/** A sheet's text field: a 52 dp pill; Done hides the keyboard (rule 4). */
@Composable
internal fun SheetField(value: String, onValue: (String) -> Unit, hint: String, icon: Boolean = false, onDone: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp).clip(CircleShape).background(c.surfaceContainerHigh).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) { Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)) }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, color = c.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = value, onValueChange = onValue, singleLine = true,
                textStyle = OwnDirectionText.merge(TextStyle(color = c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Subscriptions' group chips: All · groups · + Group (a long-press on a group edits it). A row that scrolls (0.5.65). */
@Composable
fun SubscriptionGroupChips(onEdit: (SubscriptionGroup?) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)) {
        item(key = "all") { FilterPill(stringResource(R.string.feed_group_all), selected = SubscriptionGroups.selected == null) { SubscriptionGroups.selected = null } }
        items(SubscriptionGroups.groups, key = { it.id }) { g ->
            Box(Modifier.animateItem()) {
                FilterPill(g.name, selected = SubscriptionGroups.selected == g.id, onLongClick = { onEdit(g) }) {
                    SubscriptionGroups.selected = if (SubscriptionGroups.selected == g.id) null else g.id
                }
            }
        }
        item(key = "add") { AddPill(stringResource(R.string.feed_group_add)) { onEdit(null) } }
    }
}

/** "Unwatched only", under the channel row: primary container with a check while on. */
@Composable
fun UnwatchedChip() {
    val c = MaterialTheme.colorScheme
    val on = SubscriptionGroups.unwatchedOnly
    val bg by animateColorAsState(if (on) c.primaryContainer else c.surfaceContainerHigh, tween(200), label = "unwatchedBg")
    val ink by animateColorAsState(if (on) c.onPrimaryContainer else c.onSurfaceVariant, tween(200), label = "unwatchedInk")
    Row(
        Modifier.padding(start = 16.dp, bottom = 10.dp).height(34.dp).pressable { SubscriptionGroups.chooseUnwatchedOnly(!on) }
            .clip(CircleShape).background(bg).padding(start = if (on) 10.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) { Icon(Icons.Rounded.Check, null, tint = ink, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)) }
        Text(stringResource(R.string.feed_unwatched_only), color = ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

/** The header's "Your feed" button: always the round action's plain tone (Derek: never coloured while a rule is on). */
@Composable
fun FeedFilterAction(onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(44.dp).pressable(onClick = onClick).clip(CircleShape).background(c.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Tune, stringResource(R.string.feed_filters), tint = c.onSurface, modifier = Modifier.size(22.dp))
    }
}

/** Everything loaded is hidden by the phone's rules: say so, with the way to change them and to look further. */
@Composable
fun FilteredOut(title: String, body: String, editLabel: String, onEdit: () -> Unit, onMore: (() -> Unit)?) {
    EmptyState(
        icon = Icons.Rounded.FilterListOff,
        title = title,
        body = body,
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PillButton(editLabel, onEdit)
                // Only while the feed has more pages to look through.
                if (onMore != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.feed_load_more), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                        modifier = Modifier.pressable(onClick = onMore).padding(8.dp),
                    )
                }
            }
        },
    )
}

/** Subscriptions' Shorts (#96) on Subscriptions and Home, when "Shorts from subscriptions" is on: 9:16 tiles in a row; a tap plays them in the Shorts pager. */
@Composable
fun SubscriptionShortsRow(shorts: List<VideoItem>, onOpen: (Int) -> Unit) {
    Column(Modifier.padding(bottom = 8.dp)) {
        SectionTitle(stringResource(R.string.ui_shorts), Modifier)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shorts.size, key = { shorts[it].id }) { i ->
                val v = shorts[i]
                Column(Modifier.width(112.dp).pressable(pressedScale = 0.97f) { onOpen(i) }) {
                    Thumbnail(v.thumbnail, Modifier.fillMaxWidth().aspectRatio(9f / 16f), radius = 18)
                    Spacer(Modifier.height(6.dp))
                    Text(v.title, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
