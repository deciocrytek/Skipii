package com.decio.skipii

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Pages in a row that add nothing to show before a narrowed feed stops paging by itself ([FeedList]). */
private const val DryLoadsMax = 3

/** A pageable list of videos (the account's home, subscriptions, history; a playlist; a channel's tab). */
@Stable
class FeedState(
    private val scope: CoroutineScope,
    /** Warm up the stream data of the first videos once a page lands (Home, Subscriptions). */
    private val prefetchTop: Int = 0,
    /**
     * Home: every refresh should bring different suggestions, as in the YouTube app. The
     * signed-in web feed returns much the same first page each time, so a refresh reads
     * on into the recommendation stream and keeps only videos not yet shown this session.
     */
    private val freshOnRefresh: Boolean = false,
    private val loader: suspend (next: String?) -> FeedPage,
) {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Bumped when a refresh lands; lists scroll back to the top on it. */
    var generation by mutableStateOf(0)
        private set
    /** A pull/button refresh is running (the first load shows its own spinner). */
    var refreshing by mutableStateOf(false)
        private set
    /** The category chips YouTube put over the feed's first page (Home); empty when it sent none. */
    var chips by mutableStateOf<List<FeedChip>>(emptyList())
        private set
    /** The first page's Shorts (Subscriptions' shelf, #96); empty for other feeds. */
    var shorts by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    /** The chip whose feed is shown; null = the plain feed (the chip without a token, "All"). */
    var chip by mutableStateOf<FeedChip?>(null)
        private set
    private var next: String? = null
    private var loaded = false
    /** When the first page shown last landed (elapsedRealtime); 0 before. */
    private var landedAt = 0L
    /** Ids already put in front of the user this session (fresh-refresh feeds only). */
    private val shown = HashSet<String>()
    private var job: Job? = null
    val hasMore get() = next != null
    /** The next page's token (for a pager that pages on by itself, like Shorts over a channel's list). */
    val nextToken get() = next
    /** Not asked for yet: about to load (a list shows its spinner from the first frame, never nothing). */
    val waiting get() = !loaded
    /** Loaded, and YouTube sent no videos at all (no error either). */
    val loadedEmpty get() = loaded && !loading && error == null && videos.isEmpty()

    fun ensureLoaded() { if (!loaded && !loading) refresh() }

    /** Takes video [id] out of the list at once (removed from a playlist); where it was, or -1. */
    fun removeLocally(id: String): Int {
        val i = videos.indexOfFirst { it.id == id }
        if (i >= 0) videos = videos.toMutableList().also { it.removeAt(i) }
        return i
    }

    /** Puts [video] back at [index] (an Undo, or the account refused the removal). */
    fun putBack(index: Int, video: VideoItem) {
        if (videos.any { it.id == video.id }) return
        videos = videos.toMutableList().also { it.add(index.coerceIn(0, it.size), video) }
    }

    /** The first page shown landed more than [ms] ago (false while nothing has). */
    fun olderThan(ms: Long) = landedAt != 0L && android.os.SystemClock.elapsedRealtime() - landedAt > ms

    /** Pull/button refresh: reloads what's shown, the chosen chip's feed included. */
    fun refresh() = load(fresh = true)

    /**
     * Shows [pick]'s feed from its first page. The plain chip ("All"), or the chip already
     * shown, goes back to the plain feed. The chips row stays as it is meanwhile.
     */
    fun selectChip(pick: FeedChip) {
        chip = if (pick.token == null || pick.title == chip?.title) null else pick
        videos = emptyList(); next = null
        load(fresh = false)
    }

    /**
     * Loads the first page of what's chosen. The plain feed takes its chips from the answer;
     * [fresh]: a refresh of a fresh-on-refresh feed reads on to unseen videos. A chip's feed is
     * a continuation of the plain one, so its first page is the loader's page for its token.
     */
    private fun load(fresh: Boolean) {
        loaded = true
        loading = true
        refreshing = videos.isNotEmpty()
        error = null
        job?.cancel()
        val pick = chip
        job = scope.launch {
            val result = runCatching {
                when {
                    pick?.token != null -> loader(pick.token)
                    fresh && freshOnRefresh && shown.isNotEmpty() -> loadFresh()
                    else -> loader(null)
                }
            }
            if (!isActive) return@launch // a newer load (another chip) took over
            result
                .onSuccess {
                    videos = it.videos; next = it.next; generation++
                    landedAt = android.os.SystemClock.elapsedRealtime()
                    if (pick == null) {
                        chips = it.chips
                        shorts = it.shorts
                        if (freshOnRefresh) it.videos.forEach { v -> shown += v.id }
                    }
                    it.videos.take(prefetchTop).forEach { v -> StreamCache.prefetch(v.id) }
                }
                .onFailure { error = friendly(it) }
            loading = false
            refreshing = false
        }
    }

    fun loadMore() {
        val token = next ?: return
        if (loading) return
        loading = true
        error = null
        val pick = chip
        job = scope.launch {
            val result = runCatching { loader(token) }
            if (!isActive) return@launch
            result
                .onSuccess { page ->
                    val seen = videos.mapTo(HashSet()) { it.id }
                    val added = page.videos.filter { seen.add(it.id) }
                    videos = videos + added
                    if (freshOnRefresh && pick == null) added.forEach { v -> shown += v.id }
                    next = page.next
                }
                .onFailure { error = friendly(it) }
            loading = false
        }
    }

    /** [loadAll] is paging through to the end. */
    var loadingAll by mutableStateOf(false)
        private set

    /**
     * Pages on to the end of the list, each page shown as it lands: a search inside Watch later / Liked videos
     * has to see every video (1,667 of them in 17 pages, ~11 s, on Derek's Watch later, 2026-11-20). Waits for a
     * load already running; a refresh cancels it ([job]); stops at the first failure ([error] set, Try again
     * calls this again).
     */
    fun loadAll() {
        if (next == null || loadingAll) return
        loadingAll = true
        error = null
        val previous = job
        job = scope.launch {
            try {
                previous?.join()
                loading = true
                while (isActive) {
                    val token = next ?: break
                    val result = runCatching { loader(token) }
                    if (!isActive) return@launch
                    val page = result.getOrElse { error = friendly(it); break }
                    val seen = videos.mapTo(HashSet()) { it.id }
                    videos = videos + page.videos.filter { seen.add(it.id) }
                    next = page.next
                }
                loading = false
            } finally {
                loadingAll = false
            }
        }
    }

    /**
     * Unseen videos only: reads up to [FRESH_PAGES] pages into the feed until there are
     * [FRESH_TARGET]. If YouTube has nothing new at all, falls back to the plain first page.
     */
    private suspend fun loadFresh(): FeedPage {
        val first = loader(null)
        val fresh = LinkedHashMap<String, VideoItem>()
        var token = first.next
        first.videos.filter { it.id !in shown }.forEach { fresh[it.id] = it }
        var pages = 1
        while (fresh.size < FRESH_TARGET && token != null && pages < FRESH_PAGES) {
            val page = loader(token)
            page.videos.filter { it.id !in shown }.forEach { fresh.putIfAbsent(it.id, it) }
            token = page.next
            pages++
        }
        if (fresh.isEmpty()) return first
        return FeedPage(fresh.values.toList(), token, first.chips)
    }

    fun reset() {
        job?.cancel()
        shown.clear()
        videos = emptyList(); next = null; loaded = false; loading = false; error = null
        chips = emptyList(); chip = null; landedAt = 0L; shorts = emptyList()
    }

    private companion object {
        const val FRESH_TARGET = 16
        const val FRESH_PAGES = 5
    }

    private fun friendly(t: Throwable) = when (t) {
        is NotSignedInException -> str(R.string.ui_signed_out_error)
        is YouTubeRefusedException -> t.localizedMessage
        else -> t.message?.takeIf { it.startsWith("YouTube") } ?: YouTube.friendlyError(t)
    }
}

/**
 * Everything the signed-in tabs show, kept above the tab switcher so it survives tab changes.
 * Subscriptions and its channel row are read ahead in the background once Home is in
 * ([warmUp]), and the row is kept on disk ([CHANNELS_PREFS]) so it's there from the first frame
 * on the next start: both used to wait for the tab, the row a few seconds after the videos.
 */
@Stable
class LibraryData(scope: CoroutineScope, context: Context) {
    val home = FeedState(scope, prefetchTop = 2, freshOnRefresh = true) { InnerTube.home(it) }
    val subscriptions = FeedState(scope, prefetchTop = 2) { InnerTube.subscriptionsFeed(it) }
    val history = FeedState(scope) { InnerTube.history(it) }
    private val prefs = context.getSharedPreferences(CHANNELS_PREFS, Context.MODE_PRIVATE)
    var channels by mutableStateOf(readChannels())
        private set
    var playlists by mutableStateOf<List<PlaylistRef>>(emptyList())
        private set
    private val scope = scope
    private var channelsJob: Job? = null
    /** Subscriptions has been on screen since it was last (re)loaded. */
    private var subscriptionsShown = false

    /** Once per session (the kept row shows meanwhile); again only after [reset]. */
    fun ensureChannels() {
        if (channelsJob != null) return
        channelsJob = scope.launch {
            runCatching { InnerTube.channels() }
                .onSuccess { channels = it; writeChannels(it) }
                .onFailure { channelsJob = null } // the next look at the tab tries again
        }
    }
    fun loadPlaylists() = scope.launch { runCatching { InnerTube.playlists() }.onSuccess { playlists = it } }

    /**
     * Signed in: reads Subscriptions and its channel row ahead, once Home's first page is in (so
     * it never competes with what's on screen), or after [WARM_UP_WAIT_MS] if Home isn't asked for.
     */
    suspend fun warmUp() {
        withTimeoutOrNull(WARM_UP_WAIT_MS) { snapshotFlow { home.waiting || home.loading }.first { !it } }
        if (!Account.signedIn) return
        subscriptions.ensureLoaded()
        ensureChannels()
    }

    /**
     * The Subscriptions tab is on screen. A page read ahead long ago is reloaded before it's seen,
     * and so is one whose read-ahead failed (a blip at start shouldn't greet the tab with an error).
     */
    fun showSubscriptions() {
        val failedAhead = subscriptions.error != null && subscriptions.videos.isEmpty() && !subscriptions.loading
        if (!subscriptionsShown && (failedAhead || subscriptions.olderThan(STALE_AHEAD_MS))) subscriptions.refresh()
        else subscriptions.ensureLoaded()
        subscriptionsShown = true
        ensureChannels()
    }

    /** Home shows the subscriptions' Shorts row: load the feed, or reload a page read over 10 min ago. */
    fun subscriptionsForHome() {
        if (subscriptions.loading) return
        if (subscriptions.olderThan(STALE_AHEAD_MS)) subscriptions.refresh() else subscriptions.ensureLoaded()
    }

    fun playlistFeed(id: String) = FeedState(scope) { InnerTube.playlist(id, it) }

    fun reset() {
        home.reset(); subscriptions.reset(); history.reset()
        channelsJob?.cancel(); channelsJob = null; subscriptionsShown = false
        channels = emptyList(); playlists = emptyList()
        prefs.edit().remove(KEY_CHANNELS).apply()
    }

    private fun readChannels(): List<ChannelRef> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_CHANNELS, null) ?: return emptyList())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ChannelRef(o.getString("id"), o.optString("name"), o.optString("avatar").ifBlank { null })
        }
    }.getOrElse { prefs.edit().remove(KEY_CHANNELS).apply(); emptyList() }

    private fun writeChannels(list: List<ChannelRef>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar ?: "")) }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    private companion object {
        const val CHANNELS_PREFS = "library"
        const val KEY_CHANNELS = "channels"
        const val WARM_UP_WAIT_MS = 6_000L
        /** A Subscriptions page read ahead this long before the tab is first opened is read again. */
        const val STALE_AHEAD_MS = 10 * 60_000L
    }
}

/**
 * A list state that animates back to the top whenever [signal] changes (tapping the
 * already-selected tab). The value it was created with is ignored, so merely showing a
 * tab never scrolls it.
 */
@Composable
fun rememberScrollToTop(signal: Int): LazyListState {
    val state = rememberLazyListState()
    val initial = remember { signal }
    LaunchedEffect(signal) { if (signal != initial) state.animateScrollToItem(0) }
    return state
}

/** The narrowest a card gets in a grid: one a row on a phone, two on an open Fold, four on a landscape tablet. */
private val GridCardMin = 280.dp

/**
 * Cards a row on this window (Rail 3 grids): the page's width (right of the rail) over [GridCardMin], at most four.
 * A wide window always gets two: an upright 600–643 dp tablet (Galaxy Tab A, Lenovo Tab M8) has under 560 dp
 * right of the rail and showed one phone card stretched across it (a Threads report, 2026-11-04).
 */
@Composable
fun gridColumns(): Int {
    val window = LocalWindowLayout.current
    val width = LocalConfiguration.current.screenWidthDp.dp - if (window.wide) RailWidth else 0.dp
    // A TV is seen from across the room at 960 dp: four a row, as TV apps do (three at 280 looked like a tablet's).
    return (width / if (window.tv) TvCardMin else GridCardMin).toInt().coerceIn(if (window.wide) 2 else 1, 4)
}

/** A TV's narrowest card (Android TV, Tv.kt). */
private val TvCardMin = 200.dp

/**
 * Video cards, [columns] a row: a grid kept inside the page's LazyColumn, so headers and the
 * trailing loader stay ordinary items. One column is exactly the phone's list.
 */
fun LazyListScope.videoCards(
    videos: List<VideoItem>,
    columns: Int,
    onOpen: (VideoItem) -> Unit,
    /** A card of its own in the grid's second spot (Home's Shorts card); grids only. */
    extra: (@Composable (Modifier) -> Unit)? = null,
    /** Given to the first card, so a TV remote can be put there (fresh search results). */
    firstFocus: FocusRequester? = null,
    /** A card swiped left is taken out (a playlist of the account's, #85). */
    onSwipeAway: ((VideoItem) -> Unit)? = null,
) {
    val videos = videos.filterNot(Dismissed::hides)
    fun Modifier.first(video: VideoItem) = if (firstFocus != null && video === videos.firstOrNull()) focusRequester(firstFocus) else this
    // Swipe right to queue on every card, swipe left to take it out where the list allows (Swipe.kt).
    @Composable
    fun Card(video: VideoItem, modifier: Modifier) = SwipeVideo(
        video,
        modifier = modifier,
        inset = VideoCardInset,
        corner = VideoCardCorner,
        onSwipeLeft = onSwipeAway?.let { remove -> { remove(video) } },
    ) {
        VideoCard(video, onClick = { onOpen(video) }, modifier = Modifier.first(video))
    }
    if (columns <= 1) {
        items(videos, key = { it.id }) { video ->
            Card(video, if (onSwipeAway != null) Modifier.animateItem() else Modifier)
        }
        return
    }
    val cells: List<Any> = if (extra != null && videos.isNotEmpty()) videos.take(1) + ExtraCell + videos.drop(1) else videos
    // A row keeps its first video's key (always a video), so rows already shown stay put as more load.
    items(cells.chunked(columns), key = { row -> "row-" + (row.first() as VideoItem).id }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp)) {
            row.forEach { cell ->
                if (cell is VideoItem) Card(cell, Modifier.weight(1f))
                else extra?.invoke(Modifier.weight(1f))
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * Takes [video] out of [playlist] (#65 / #85): gone from [feed] at once, with an Undo; the account is told once
 * the Undo has had its time (so an Undo never needs a second request), and the card comes back if it refuses.
 */
fun removeFromPlaylist(scope: CoroutineScope, feed: FeedState, playlist: PlaylistRef, video: VideoItem, player: PlayerState) {
    val at = feed.removeLocally(video.id)
    if (at < 0) return
    val name = if (playlist.id == "WL") str(R.string.ui_watch_later) else playlist.title
    val job = scope.launch {
        kotlinx.coroutines.delay(UndoWindowMs)
        runCatching { InnerTube.setInPlaylist(playlist.id, video.id, add = false) }
            .onFailure {
                AppLog.w("Skipii", "remove ${video.id} from ${playlist.id}", it)
                feed.putBack(at, video)
                player.actionMessage = str(R.string.ui_remove_failed, name)
            }
    }
    val message = str(R.string.ui_removed_from, name)
    player.actionMessage = message
    player.actionUndo = message to { job.cancel(); feed.putBack(at, video) }
}

/** How long a removal can be undone before it's sent (the message stays that long). */
const val UndoWindowMs = 4_000L

/** Where [videoCards]' extra card goes among the videos. */
private object ExtraCell

/**
 * Cards taken out with "Not interested" / "Don't recommend channel" (#16), gone from every feed at once.
 * Kept for this run of the app only: the account remembers, so the next load leaves them out itself.
 */
object Dismissed {
    private val videos = mutableStateMapOf<String, Unit>()
    private val channels = mutableStateMapOf<String, Unit>()

    fun hides(video: VideoItem) = video.id in videos || (video.channelUrl?.let { it in channels } == true)

    fun hide(video: VideoItem, channel: Boolean) {
        if (channel) video.channelUrl?.let { channels[it] = Unit } else videos[video.id] = Unit
    }

    fun restore(video: VideoItem, channel: Boolean) {
        if (channel) video.channelUrl?.let(channels::remove) else videos.remove(video.id)
    }
}

/** A feed as a column of video cards with infinite scroll and designed loading/error states. */
@Composable
fun FeedList(
    feed: FeedState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    header: LazyListScope.() -> Unit = {},
    scrollToTop: Int = 0,
    pullToRefresh: Boolean = true,
    /** Collapsing header height: the refresh spinner appears just below it. */
    indicatorTop: Dp = 0.dp,
    /** A card of its own in a grid's second spot (wide windows only). */
    gridExtra: (@Composable (Modifier) -> Unit)? = null,
    /** Cards swiped left come out ([SwipeVideo]). */
    onSwipeAway: ((VideoItem) -> Unit)? = null,
    /** What's shown: the feed's videos, or a search's matches among them. */
    videos: List<VideoItem> = feed.videos,
    /** A change scrolls back to the top, like a refresh (a new search). */
    scrollKey: Any? = null,
    /** The trailing spinner while the feed loads / has more. */
    showLoader: Boolean = true,
    /** Items after the videos (a search's "no matches"). */
    footer: LazyListScope.() -> Unit = {},
    /**
     * [videos] are the feed narrowed by the phone's own rules (Your feed, a subscription group): after
     * [DryLoadsMax] pages in a row that add nothing to show, paging stops and [filteredEmpty] (nothing shown at
     * all) or a Load more row takes the loader's place, so a rule that hides everything can't page on forever.
     */
    filtered: Boolean = false,
    filteredEmpty: (@Composable (loadMore: (() -> Unit)?) -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val list = rememberScrollToTop(scrollToTop)
    // Fresh content always starts at the top (first load, every refresh, a new search).
    LaunchedEffect(feed.generation, scrollKey) { list.scrollToItem(0) }
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    // Pages in a row that added nothing to show (only counted while [filtered]): counted per page that LANDS
    // (a load finishing without an error), so pages of duplicates and a rule that hides everything count too.
    var dry by remember(feed, scrollKey) { mutableIntStateOf(0) }
    val shown = videos.size
    val shownNow by rememberUpdatedState(shown)
    var shownAtLoad by remember(feed) { mutableIntStateOf(-1) }
    // Pages landed: the paging effect looks again after each, even when one added nothing (all duplicates).
    var landed by remember(feed) { mutableIntStateOf(0) }
    LaunchedEffect(feed.loading) {
        if (feed.loading) { shownAtLoad = shownNow; return@LaunchedEffect }
        if (shownAtLoad < 0) return@LaunchedEffect
        if (feed.error == null) { dry = if (filtered && shownNow == shownAtLoad) dry + 1 else 0; landed++ }
        shownAtLoad = -1
    }
    // Something new to show (a page, or a rule loosened): paging may go on.
    LaunchedEffect(shown) { if (shown > 0) dry = 0 }
    LaunchedEffect(feed.generation, filtered) { dry = 0 }
    val paused = filtered && dry >= DryLoadsMax && feed.hasMore
    // Keyed on what's shown and [scrollKey] too: switching to a group with nothing near the end still pages.
    LaunchedEffect(nearEnd, feed.videos.size, shown, scrollKey, paused, landed) {
        if (nearEnd && feed.videos.isNotEmpty() && !paused && feed.error == null) feed.loadMore()
    }
    val loadOn = { dry = 0; feed.loadMore() }
    val columns = gridColumns()
    PullToRefresh(enabled = pullToRefresh, refreshing = feed.refreshing, onRefresh = feed::refresh, topPadding = indicatorTop) {
    LazyColumn(state = list, contentPadding = contentPadding) {
        header()
        videoCards(videos, columns, onOpen, extra = gridExtra, onSwipeAway = onSwipeAway)
        when {
            // No keys on these trailing items: a keyed loader at index 0 makes LazyColumn
            // hold it in view when videos arrive above it, landing at the bottom of the page.
            feed.error != null && feed.videos.isEmpty() -> item {
                // Offline with downloads kept: they're one tap away (Neon's spec).
                val openDownloads = LocalDownloadsOpener.current?.takeIf { Downloads.entries.values.any { it.ready } }
                EmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = stringResource(R.string.ui_feed_error_title),
                    body = feed.error.orEmpty(),
                    action = {
                        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PillButton(stringResource(R.string.ui_try_again), { feed.refresh() })
                            if (openDownloads != null) {
                                Spacer(Modifier.size(10.dp))
                                androidx.compose.material3.Text(
                                    stringResource(R.string.dl_open), color = c.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                                    fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
                                    modifier = Modifier.pressable(onClick = openDownloads).padding(8.dp),
                                )
                            }
                        }
                    },
                )
            }
            // A later page failed: say so where the loader was, with a way to try again (it'd spin forever).
            // (Not when the caller shows its own error there, [showLoader] off: a list search's "load all".)
            showLoader && feed.error != null && !feed.loading -> item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Text(feed.error.orEmpty(), color = c.onSurfaceVariant, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
                    Spacer(Modifier.size(10.dp))
                    // A failed refresh of a feed with no more pages: refresh again (loading more would do nothing).
                    PillButton(stringResource(R.string.ui_try_again), { if (feed.hasMore) loadOn() else feed.refresh() })
                }
            }
            // A rule hides everything: once paging paused, or the feed has no more pages to look through.
            filtered && videos.isEmpty() && feed.videos.isNotEmpty() && !feed.loading && filteredEmpty != null && (paused || !feed.hasMore) -> item {
                filteredEmpty(loadOn.takeIf { feed.hasMore })
            }
            paused && !feed.loading -> item {
                Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) { PillButton(stringResource(R.string.feed_load_more), loadOn) }
            }
            showLoader && (feed.loading || feed.hasMore) -> item {
                Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                }
            }
        }
        footer()
    }
    }
}

/**
 * Swipe down at the top to reload (replaces the old refresh button). The indicator uses
 * the theme's colors and sits just under the screen header.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PullToRefresh(enabled: Boolean, refreshing: Boolean, onRefresh: () -> Unit, topPadding: Dp = 0.dp, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val c = MaterialTheme.colorScheme
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pull,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pull, isRefreshing = refreshing,
                containerColor = c.primaryContainer, color = c.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
            )
        },
    ) { content() }
}
