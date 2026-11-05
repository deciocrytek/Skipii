package com.decio.skipii

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Opens a channel page from anywhere (player channel row, card avatars). Provided by the shell. */
val LocalChannelOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

/** The channel's id when its address already says it (/channel/UC…), so its page can load alongside NewPipe's details. */
private fun channelIdIn(url: String): String? =
    url.substringAfter("/channel/", "").takeWhile { it != '/' && it != '?' }.takeIf { it.startsWith("UC") && it.length == 24 }

/**
 * A channel page (GitHub #53, Derek picked A "Underline tabs" in docs/design/channel-page.html):
 * the profile, then YouTube's tabs — Home (the creator's shelves), Videos (Latest / Popular /
 * Oldest), Shorts (played in the Shorts pager) and Playlists — each loaded the first time it's shown.
 */
@Stable
class ChannelState(val url: String, private val scope: CoroutineScope) {
    var details by mutableStateOf<ChannelDetails?>(null)
        private set
    /** The page's Home tab (tabs, featured video, shelves); null until loaded or if it couldn't be read. */
    var home by mutableStateOf<ChannelHome?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var subscribed by mutableStateOf<Boolean?>(null)
        private set
    /** The tab picked; [current] is the one shown (Home first, as on YouTube — Derek). */
    private var tab by mutableStateOf(ChannelTab.Home)
    /** The page's scroll, kept here so that back from one of its shorts the page is where it was. */
    val list = LazyListState()

    val videos = FeedState(scope) { token -> filled(InnerTube.channelVideos(channelId(), token)) }
    val shorts = FeedState(scope) { token -> InnerTube.channelShorts(channelId(), tabParams(ChannelTab.Shorts), token) }
    var playlists by mutableStateOf<List<PlaylistRef>>(emptyList())
        private set
    var playlistsLoading by mutableStateOf(false)
        private set
    var playlistsError by mutableStateOf<String?>(null)
        private set
    private var playlistsNext: String? = null
    private var playlistsLoaded = false
    private var playlistsJob: Job? = null
    val playlistsHaveMore get() = playlistsNext != null
    val playlistsEmpty get() = playlistsLoaded && !playlistsLoading && playlistsError == null && playlists.isEmpty()
    /** The Playlists tab has asked at least once (before that it shows its spinner). */
    val playlistsTried get() = playlistsLoaded

    /** Home's shelves as shown: Shorts shelves only while Shorts are on. */
    val shelves: List<ChannelShelf>
        get() = home?.shelves.orEmpty().filter { ShortsSetting.enabled || it.shorts.isEmpty() }

    /**
     * The tabs this channel has, in YouTube's order: Home only with something on it, Shorts only
     * while Shorts are on. Without the page (it couldn't be read), the uploads alone, as before.
     */
    val tabs: List<ChannelTab>
        get() {
            val h = home ?: return listOf(ChannelTab.Videos)
            val shown = ChannelTab.entries.filter { t ->
                when (t) {
                    ChannelTab.Home -> h.featured != null || shelves.isNotEmpty()
                    ChannelTab.Shorts -> ShortsSetting.enabled && t in h.tabs
                    else -> t in h.tabs
                }
            }
            return shown.ifEmpty { listOf(ChannelTab.Videos) }
        }

    val current: ChannelTab get() = tabs.let { t -> tab.takeIf { it in t } ?: t.first() }

    init { load() }

    private fun channelId() = details?.id ?: error("channel not loaded")
    private fun tabParams(t: ChannelTab) = home?.tabs?.get(t) ?: error("no ${t.title} tab")

    /** The uploads list doesn't repeat the channel on every item; fill it in. */
    private fun fill(v: VideoItem): VideoItem {
        val d = details ?: return v
        return v.copy(channel = v.channel.ifBlank { d.name }, avatar = v.avatar ?: d.avatar, channelUrl = v.channelUrl ?: d.url)
    }

    private fun filled(page: FeedPage) = page.copy(videos = page.videos.map(::fill))

    fun load() {
        loading = true
        error = null
        scope.launch {
            // Signed out, the page loads alongside NewPipe's details when the address has the id. Signed
            // in it waits for the channel's name and picture, which go to Bells with its bell.
            val early = channelIdIn(url)?.takeIf { !Account.signedIn }?.let { id -> async { runCatching { InnerTube.channelHome(id, null, null) } } }
            runCatching { YouTube.channel(url) }
                .onSuccess { d ->
                    val page = early?.await() ?: runCatching { InnerTube.channelHome(d.id, d.name, d.avatar) }
                    page.exceptionOrNull()?.let { if (it is CancellationException) throw it; Log.w("Skipii", "channel: couldn't read ${d.id}'s page; uploads only", it) }
                    // Profile and tabs land together, so the page never shifts as the tabs arrive.
                    details = d
                    home = page.getOrNull()?.let { h ->
                        h.copy(featured = h.featured?.let(::fill), shelves = h.shelves.map { s -> s.copy(videos = s.videos.map(::fill)) })
                    }
                    subscribed = home?.subscribed
                }
                .onFailure { if (it is CancellationException) throw it; error = YouTube.friendlyError(it) }
            loading = false
        }
    }

    fun show(t: ChannelTab) { tab = t }

    /** What [t] shows, loaded the first time it's shown. */
    fun ensureLoaded(t: ChannelTab) {
        if (details == null) return
        when (t) {
            ChannelTab.Videos -> videos.ensureLoaded()
            ChannelTab.Shorts -> shorts.ensureLoaded()
            ChannelTab.Playlists -> if (!playlistsLoaded) loadPlaylists(more = false)
            ChannelTab.Home -> Unit
        }
    }

    fun loadMore() {
        when (current) {
            ChannelTab.Videos -> videos.loadMore()
            ChannelTab.Shorts -> shorts.loadMore()
            ChannelTab.Playlists -> loadPlaylists(more = true)
            ChannelTab.Home -> Unit
        }
    }

    fun loadPlaylists(more: Boolean) {
        val token = if (more) playlistsNext ?: return else null
        if (playlistsLoading) return
        playlistsLoaded = true
        playlistsLoading = true
        playlistsError = null
        playlistsJob?.cancel()
        playlistsJob = scope.launch {
            runCatching { InnerTube.channelPlaylists(channelId(), tabParams(ChannelTab.Playlists), token) }
                .onSuccess { p ->
                    playlists = if (token == null) p.playlists else (playlists + p.playlists).distinctBy { it.id }
                    playlistsNext = p.next
                }
                .onFailure { if (it is CancellationException) throw it; playlistsError = YouTube.friendlyError(it) }
            playlistsLoading = false
        }
    }

    /** The Shorts tab's shorts for the pager, from [start]; it pages on through the channel by itself. */
    fun shortsFrom(start: Int): ShortsList {
        val id = channelId()
        val params = tabParams(ChannelTab.Shorts)
        return ShortsList(shorts.videos, start, shorts.nextToken) { token -> InnerTube.channelShorts(id, params, token) }
    }

    fun toggleSubscribe(onError: (String) -> Unit) {
        val id = details?.id ?: return
        val before = subscribed ?: return
        subscribed = !before
        scope.launch {
            runCatching { InnerTube.setSubscribed(id, !before) }
                .onSuccess { if (before) Bells.forget(id) }
                .onFailure { subscribed = before; onError(str(R.string.ui_subscription_error)) }
        }
    }
}

private const val ProfileKey = "header"
private const val TabsKey = "tabs"

@Composable
fun ChannelScreen(
    state: ChannelState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpen: (VideoItem) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onMessage: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val columns = gridColumns()
    val list = state.list
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val statusPx = with(density) { statusTop.toPx() }
    val tabs = state.tabs
    val current = state.current
    val hasTabs = tabs.size > 1
    LaunchedEffect(state.details, current) { state.ensureLoaded(current) }
    val shownCount = when (current) {
        ChannelTab.Videos -> state.videos.videos.size
        ChannelTab.Shorts -> state.shorts.videos.size
        ChannelTab.Playlists -> state.playlists.size
        ChannelTab.Home -> 0
    }
    val nearEnd by remember {
        derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 }
    }
    LaunchedEffect(nearEnd, current, shownCount) { if (nearEnd && shownCount > 0) state.loadMore() }

    // Where the list's content starts (the header's height), for keeping the tabs in place on a switch.
    var topPx by remember { mutableFloatStateOf(0f) }
    /** The tabs row has scrolled up to (or under) [line]. */
    fun tabsAbove(line: Float): Boolean {
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == TabsKey } ?: return list.firstVisibleItemIndex > 1
        // A pixel's slack: a switch scrolls the row onto the line exactly.
        return item.offset - info.viewportStartOffset < line + 1f
    }
    // Once the tabs are pinned, a new tab (or order) starts right under them, as YouTube's does. The
    // list keeps its place by the item under the header's edge, which the new content replaces (a
    // spinner, then the sort chips, then videos), so the tabs are held there until a finger scrolls.
    var holdTabs by remember { mutableStateOf(false) }
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) holdTabs = false } }
    LaunchedEffect(holdTabs, current, shownCount, state.videos.chips.size, state.videos.chip) {
        if (holdTabs) list.scrollToItem(1, (topPx - statusPx).roundToInt())
    }
    fun holdIfPinned() { if (tabsAbove(topPx)) holdTabs = true }
    fun pick(t: ChannelTab) {
        if (t == current) return
        holdIfPinned()
        state.show(t)
    }
    val tabLabels = tabs.map { tabLabel(it) }

    Box(Modifier.fillMaxSize().background(c.surface)) {
    CollapsingHeaderLayout(
        header = {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                state.details?.name.orEmpty(),
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, letterSpacing = (-0.6).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        },
        // The tabs stick under the header once the profile has scrolled away (under the status bar
        // while the header is hidden), with a soft fade under them.
        pinned = if (!hasTabs) null else { headerBottom ->
            val pinned by remember(statusPx) { derivedStateOf { tabsAbove(maxOf(headerBottom(), statusPx)) } }
            if (pinned) {
                val page = c.surface
                val fade = remember(page) { vignetteBrush(page, opaqueAtTop = true) }
                Column(Modifier.fillMaxWidth().offset { IntOffset(0, (maxOf(headerBottom(), statusPx) - statusPx).roundToInt()) }) {
                    Box(Modifier.fillMaxWidth().background(page).padding(top = statusTop)) {
                        UnderlineTabs(tabLabels, tabs.indexOf(current), { pick(tabs[it]) }, TabsRowModifier)
                    }
                    Box(Modifier.fillMaxWidth().height(14.dp).drawBehind { drawRect(fade) })
                }
            }
        },
    ) { top ->
        SideEffect { topPx = with(density) { top.toPx() } }
        val d = state.details
        when {
            d == null && state.error != null -> Box(Modifier.padding(top = top)) { EmptyState(
                Icons.Rounded.CloudOff, stringResource(R.string.ui_channel_error_title), state.error.orEmpty(),
                action = { PillButton(stringResource(R.string.ui_try_again), state::load) },
            ) }
            d == null -> Box(Modifier.fillMaxWidth().padding(top = top + 60.dp), Alignment.Center) {
                CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
            }
            else -> LazyColumn(state = list, contentPadding = contentPadding.withTop(top)) {
                item(key = ProfileKey) { ChannelProfile(state, d, onMessage) }
                if (hasTabs) item(key = TabsKey) {
                    UnderlineTabs(tabLabels, tabs.indexOf(current), { pick(tabs[it]) }, TabsRowModifier)
                }
                when (current) {
                    ChannelTab.Home -> homeTab(state, columns, onOpen, onOpenShorts, onOpenPlaylist, onLink = { link ->
                        when (link) {
                            is ShelfLink.Tab -> pick(link.tab)
                            is ShelfLink.Playlist -> onOpenPlaylist(link.ref)
                        }
                    })
                    ChannelTab.Videos -> {
                        val feed = state.videos
                        if (feed.chips.size > 1) item(key = "sorts") { SortChips(feed.chips, feed.chip) { holdIfPinned(); feed.selectChip(it) } }
                        videoCards(feed.videos, columns, onOpen)
                        feedTail(feed.loading || feed.hasMore || feed.waiting, feed.error.takeIf { feed.videos.isEmpty() }, feed::refresh,
                            empty = if (feed.loadedEmpty) R.string.ui_channel_no_videos else null, alone = feed.videos.isEmpty())
                    }
                    ChannelTab.Shorts -> {
                        val feed = state.shorts
                        val perRow = (columns * 2).coerceAtMost(6)
                        val rows = feed.videos.withIndex().chunked(perRow)
                        items(rows, key = { "shorts-" + it.first().value.id }) { row ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { (i, s) -> ShortTile(s, Modifier.weight(1f)) { onOpenShorts(state.shortsFrom(i)) } }
                                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                        feedTail(feed.loading || feed.hasMore || feed.waiting, feed.error.takeIf { feed.videos.isEmpty() }, feed::refresh,
                            empty = if (feed.loadedEmpty) R.string.ui_channel_no_shorts else null, alone = feed.videos.isEmpty())
                    }
                    ChannelTab.Playlists -> {
                        items(state.playlists, key = { "playlist-" + it.id }) { p -> PlaylistRow(p) { onOpenPlaylist(p) } }
                        feedTail(state.playlistsLoading || state.playlistsHaveMore || !state.playlistsTried, state.playlistsError.takeIf { state.playlists.isEmpty() },
                            { state.loadPlaylists(more = false) }, empty = if (state.playlistsEmpty) R.string.ui_channel_no_playlists else null,
                            alone = state.playlists.isEmpty())
                    }
                }
            }
        }
    }
    }
}

/** The tabs row's spacing (in the list and pinned): a little air above, the bar's room below. */
private val TabsRowModifier = Modifier.padding(top = 10.dp, bottom = 6.dp)

@Composable
private fun tabLabel(t: ChannelTab) = when (t) {
    ChannelTab.Home -> stringResource(R.string.app_tab_home)
    ChannelTab.Videos -> stringResource(R.string.ui_videos)
    ChannelTab.Shorts -> stringResource(R.string.app_tab_shorts)
    ChannelTab.Playlists -> stringResource(R.string.ui_playlists)
}

@Composable
private fun ChannelProfile(state: ChannelState, d: ChannelDetails, onMessage: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (d.banner != null) {
            Thumbnail(d.banner, Modifier.fillMaxWidth().aspectRatio(5.6f), radius = 22)
            Spacer(Modifier.height(16.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(d.avatar, d.name, size = 76)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (d.subscribers > 0) Text(subscribersLine(d.subscribers), color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        state.subscribed?.let { subscribed ->
            Spacer(Modifier.height(14.dp))
            SubscribeWithBell(
                subscribed,
                channel = BellChannel(d.id, d.name, d.avatar),
                onSubscribe = { state.toggleSubscribe(onMessage) },
                onMessage = onMessage,
                modifier = Modifier.fillMaxWidth(),
                wide = true,
            )
        }
        if (d.description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(d.description, color = c.onSurfaceVariant, fontSize = 13.5.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Home: the featured video, then the creator's shelves, each a sideways row with See all. */
private fun LazyListScope.homeTab(
    state: ChannelState,
    columns: Int,
    onOpen: (VideoItem) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onLink: (ShelfLink) -> Unit,
) {
    state.home?.featured?.let { videoCards(listOf(it), columns, onOpen) }
    state.shelves.forEachIndexed { i, shelf ->
        item(key = "shelf-$i") { ShelfRow(shelf, onOpen, onOpenShorts, onOpenPlaylist, onLink) }
    }
}

/**
 * The list's end for a tab: a spinner while there's more, the error with Try again when nothing
 * loaded, or the tab's own empty note ([empty]). No keys (rule 8: a keyed trailing loader pins the list).
 * While the tab has nothing yet ([alone]) it takes the page's height, so a tab picked from the pinned
 * row can still sit right under it (a shorter list would scroll back to the profile).
 */
private fun LazyListScope.feedTail(more: Boolean, error: String?, retry: () -> Unit, empty: Int?, alone: Boolean) {
    when {
        error != null -> item {
            Box(if (alone) Modifier.fillParentMaxHeight() else Modifier) { EmptyState(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.ui_feed_error_title),
                body = error,
                action = { PillButton(stringResource(R.string.ui_try_again), retry) },
            ) }
        }
        empty != null -> item {
            Box(if (alone) Modifier.fillParentMaxHeight() else Modifier) {
                EmptyState(icon = Icons.Rounded.VideoLibrary, title = stringResource(R.string.ui_playlist_empty_title), body = stringResource(empty))
            }
        }
        more -> item {
            Box((if (alone) Modifier.fillParentMaxHeight() else Modifier).fillMaxWidth().padding(28.dp), Alignment.TopCenter) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/**
 * The Videos tab's order as YouTube offers it (Latest · Popular · Oldest): tonal chips in a row
 * that scrolls, the chosen one full primary with rounder corners.
 */
@Composable
private fun SortChips(chips: List<FeedChip>, chosen: FeedChip?, onPick: (FeedChip) -> Unit) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 11.dp, end = 11.dp, top = 2.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            val on = chosen?.title == chip.title || (chosen == null && chip.token == null)
            val bg by animateColorAsState(if (on) c.primary else c.surfaceContainerHigh, tween(if (reduced) 0 else 220), label = "sortBg")
            val fg by animateColorAsState(if (on) c.onPrimary else c.onSurfaceVariant, tween(if (reduced) 0 else 220), label = "sortFg")
            val corner by animateDpAsState(if (on) 18.dp else 10.dp, if (reduced) tween(0) else spring(dampingRatio = 0.55f, stiffness = 500f), label = "sortCorner")
            Box(
                Modifier
                    .pressable(onClick = { if (!on) onPick(chip) })
                    .height(36.dp)
                    .clip(RoundedCornerShape(corner))
                    .background(bg)
                    .padding(horizontal = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(sortLabel(chip.title), color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** YouTube's English sort names in the app's language (others as sent). */
@Composable
private fun sortLabel(english: String) = when (english.lowercase()) {
    "latest" -> stringResource(R.string.ui_channel_sort_latest)
    "popular" -> stringResource(R.string.ui_channel_sort_popular)
    "oldest" -> stringResource(R.string.ui_channel_sort_oldest)
    else -> english
}

/** YouTube's own shelf names in the app's language; a creator's own titles stay as written. */
@Composable
private fun shelfTitle(english: String) = when (english.lowercase()) {
    "videos" -> stringResource(R.string.ui_videos)
    "shorts" -> stringResource(R.string.app_tab_shorts)
    "popular videos" -> stringResource(R.string.ui_channel_popular_videos)
    "featured channels" -> stringResource(R.string.ui_channel_featured_channels)
    "playlists", "created playlists" -> stringResource(R.string.ui_playlists)
    else -> english
}

@Composable
private fun ShelfRow(
    shelf: ChannelShelf,
    onOpen: (VideoItem) -> Unit,
    onOpenShorts: (ShortsList) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onLink: (ShelfLink) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 4.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                shelfTitle(shelf.title), Modifier.weight(1f),
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = (-0.4).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            shelf.link?.let { link ->
                Row(Modifier.pressable { onLink(link) }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.ui_see_all), color = c.primary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                shelf.videos.isNotEmpty() -> items(shelf.videos, key = { it.id }) { v -> ShelfVideo(v) { onOpen(v) } }
                shelf.shorts.isNotEmpty() -> itemsIndexed(shelf.shorts, key = { _, s -> s.id }) { i, s ->
                    ShortTile(s, Modifier.width(118.dp)) { onOpenShorts(ShortsList(shelf.shorts, i)) }
                }
                shelf.playlists.isNotEmpty() -> items(shelf.playlists, key = { it.id }) { p -> ShelfPlaylist(p) { onOpenPlaylist(p) } }
                else -> items(shelf.channels, key = { it.id }) { ch -> ShelfChannel(ch) }
            }
        }
    }
}

/** A video in a shelf: a 200 dp card, thumbnail over two lines of title and views · age. */
@Composable
private fun ShelfVideo(video: VideoItem, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val menu = LocalVideoMenu.current
    Column(
        Modifier
            .width(ShelfCardWidth)
            .prefetchOnPress(video.id)
            .pressable(pressedScale = 0.97f, onLongClick = { menu(video) }, onClick = onClick),
    ) {
        Box {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 16)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(7.dp), small = true)
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Text(
            video.title, Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            fontWeight = FontWeight.Bold, fontSize = 13.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        val meta = metaLine("", video.views, video.uploaded)
        if (meta.isNotEmpty()) Text(
            meta, Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A short: its 9:16 frame with the title and views on a dark fade at the bottom. */
@Composable
private fun ShortTile(short: VideoItem, modifier: Modifier, onClick: () -> Unit) {
    val shade = remember { Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.62f)) }
    Box(modifier.aspectRatio(9f / 16f).pressable(pressedScale = 0.97f, onClick = onClick).clip(RoundedCornerShape(18.dp))) {
        Thumbnail(short.thumbnail, Modifier.fillMaxSize(), radius = 18)
        Box(Modifier.fillMaxSize().drawBehind { drawRect(shade) })
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            Text(short.title, color = Color.White, fontSize = 12.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (short.views >= 0) Text(viewsLine(short.views), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** How many videos a playlist holds, from YouTube's English "12 videos" (just the number, in Latin digits). */
private fun playlistCount(p: PlaylistRef): String? = p.subtitle?.let { Regex("\\d[\\d,]*").find(it)?.value?.replace(",", "") }

/** The playlist mark and its count, over the thumbnail's corner. */
@Composable
private fun PlaylistBadge(p: PlaylistRef, modifier: Modifier) {
    Row(
        modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.72f)).padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
        playlistCount(p)?.let {
            Spacer(Modifier.width(3.dp))
            Text(it, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ShelfPlaylist(p: PlaylistRef, onClick: () -> Unit) {
    Column(Modifier.width(ShelfCardWidth).pressable(pressedScale = 0.97f, onClick = onClick)) {
        Box {
            Thumbnail(p.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 16)
            PlaylistBadge(p, Modifier.align(Alignment.BottomEnd).padding(7.dp))
        }
        Text(p.title, Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), fontWeight = FontWeight.Bold, fontSize = 13.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The Playlists tab's row: thumbnail with the count, the playlist's name beside it. */
@Composable
private fun PlaylistRow(p: PlaylistRef, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.97f, onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(150.dp)) {
            Thumbnail(p.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 16)
            PlaylistBadge(p, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(p.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.5.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** A channel the creator features: its picture and name; a tap opens its page. */
@Composable
private fun ShelfChannel(ch: ChannelRef) {
    val c = MaterialTheme.colorScheme
    val open = LocalChannelOpener.current
    Column(
        Modifier.width(76.dp).pressable { open("https://www.youtube.com/channel/${ch.id}") },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChannelAvatar(ch.avatar, ch.name, size = 64)
        Spacer(Modifier.height(6.dp))
        Text(ch.name, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant, maxLines = 2, lineHeight = 14.sp, overflow = TextOverflow.Ellipsis)
    }
}

private val ShelfCardWidth = 200.dp
