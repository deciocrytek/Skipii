package com.decio.skipii

import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A pageable list of videos from the signed-in account (home, subscriptions, history, a playlist). */
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
    /** The chip whose feed is shown; null = the plain feed (the chip without a token, "All"). */
    var chip by mutableStateOf<FeedChip?>(null)
        private set
    private var next: String? = null
    private var loaded = false
    /** Ids already put in front of the user this session (fresh-refresh feeds only). */
    private val shown = HashSet<String>()
    private var job: Job? = null
    val hasMore get() = next != null
    /** Loaded, and YouTube sent no videos at all (no error either). */
    val loadedEmpty get() = loaded && !loading && error == null && videos.isEmpty()

    fun ensureLoaded() { if (!loaded && !loading) refresh() }

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
                    if (pick == null) {
                        chips = it.chips
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
        chips = emptyList(); chip = null
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

/** Everything the signed-in tabs show, kept above the tab switcher so it survives tab changes. */
@Stable
class LibraryData(scope: CoroutineScope) {
    val home = FeedState(scope, prefetchTop = 2, freshOnRefresh = true) { InnerTube.home(it) }
    val subscriptions = FeedState(scope, prefetchTop = 2) { InnerTube.subscriptionsFeed(it) }
    val history = FeedState(scope) { InnerTube.history(it) }
    var channels by mutableStateOf<List<ChannelRef>>(emptyList())
        private set
    var playlists by mutableStateOf<List<PlaylistRef>>(emptyList())
        private set
    private val scope = scope

    fun loadChannels() = scope.launch { runCatching { InnerTube.channels() }.onSuccess { channels = it } }
    fun loadPlaylists() = scope.launch { runCatching { InnerTube.playlists() }.onSuccess { playlists = it } }

    fun playlistFeed(id: String) = FeedState(scope) { InnerTube.playlist(id, it) }

    fun reset() {
        home.reset(); subscriptions.reset(); history.reset()
        channels = emptyList(); playlists = emptyList()
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

/** Cards a row on this window (Rail 3 grids): the page's width (right of the rail) over [GridCardMin], at most four. */
@Composable
fun gridColumns(): Int {
    val width = LocalConfiguration.current.screenWidthDp.dp - if (LocalWindowLayout.current.wide) RailWidth else 0.dp
    return (width / GridCardMin).toInt().coerceIn(1, 4)
}

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
) {
    if (columns <= 1) {
        items(videos, key = { it.id }) { video -> VideoCard(video, onClick = { onOpen(video) }) }
        return
    }
    val cells: List<Any> = if (extra != null && videos.isNotEmpty()) videos.take(1) + ExtraCell + videos.drop(1) else videos
    // A row keeps its first video's key (always a video), so rows already shown stay put as more load.
    items(cells.chunked(columns), key = { row -> "row-" + (row.first() as VideoItem).id }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp)) {
            row.forEach { cell ->
                if (cell is VideoItem) VideoCard(cell, onClick = { onOpen(cell) }, modifier = Modifier.weight(1f))
                else extra?.invoke(Modifier.weight(1f))
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Where [videoCards]' extra card goes among the videos. */
private object ExtraCell

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
) {
    val c = MaterialTheme.colorScheme
    val list = rememberScrollToTop(scrollToTop)
    // Fresh content always starts at the top (first load and every refresh).
    LaunchedEffect(feed.generation) { list.scrollToItem(0) }
    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, feed.videos.size) { if (nearEnd && feed.videos.isNotEmpty()) feed.loadMore() }
    val columns = gridColumns()
    PullToRefresh(enabled = pullToRefresh, refreshing = feed.refreshing, onRefresh = feed::refresh, topPadding = indicatorTop) {
    LazyColumn(state = list, contentPadding = contentPadding) {
        header()
        videoCards(feed.videos, columns, onOpen, extra = gridExtra)
        when {
            // No keys on these trailing items: a keyed loader at index 0 makes LazyColumn
            // hold it in view when videos arrive above it, landing at the bottom of the page.
            feed.error != null && feed.videos.isEmpty() -> item {
                EmptyState(
                    icon = Icons.Rounded.CloudOff,
                    title = stringResource(R.string.ui_feed_error_title),
                    body = feed.error.orEmpty(),
                    action = { PillButton(stringResource(R.string.ui_try_again), { feed.refresh() }) },
                )
            }
            feed.loading || feed.hasMore -> item {
                Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                }
            }
        }
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
