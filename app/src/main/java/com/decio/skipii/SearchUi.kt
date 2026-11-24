package com.decio.skipii

import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Search tab's state (0.6.0), above the tab switcher so results survive switching tabs. [channels]: the
 * account's subscriptions (Subscriptions' row), matched locally for the channel quick-hit while typing.
 */
@Stable
class SearchState(private val scope: CoroutineScope, private val channels: () -> List<ChannelRef> = { emptyList() }) {
    var query by mutableStateOf("")
        private set
    var submitted by mutableStateOf("")
        private set
    var filters by mutableStateOf(SearchFilters())
        private set
    var hits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    /** The first page failed (the page shows the error). */
    var error by mutableStateOf<String?>(null)
        private set
    /** A later page failed: the results stay, with a Try again row under them. */
    var moreFailed by mutableStateOf(false)
        private set
    var generation by mutableStateOf(0)
        private set
    /** Bumped to put the cursor in the field (the Search widget). */
    var focusField by mutableStateOf(0)
    private var next: String? = null
    val hasMore get() = next != null

    /** What's typed so far: YouTube's suggestions and a channel, landed together (never one row at a time). */
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var quickHit by mutableStateOf<SearchHit.Channel?>(null)
        private set
    private var job: Job? = null
    private var typingJob: Job? = null

    /** A subscribed channel whose name starts with what's typed: shown at once (no request). */
    fun localChannel(): ChannelRef? {
        val q = foldWords(query.trim())
        if (q.length < 2) return null
        return channels().firstOrNull { foldWords(it.name).startsWith(q) }
    }

    fun type(text: String) {
        query = text
        typingJob?.cancel()
        val q = text.trim()
        if (q.isEmpty()) { suggestions = emptyList(); quickHit = null; return }
        typingJob = scope.launch {
            delay(SuggestDelayMs)
            // Both asked at once; the channel waits a little longer for its answer, then both land together.
            val words = async { runCatching { SearchSuggestions.forQuery(q) }.getOrDefault(emptyList()) }
            val channel = async {
                if (q.length < 3 || localChannel() != null) null
                else withTimeoutOrNull(ChannelWaitMs) {
                    runCatching { InnerTube.search(q, SearchFilters(type = SearchType.Channels)) }.getOrNull()
                        ?.hits?.filterIsInstance<SearchHit.Channel>()?.firstOrNull { foldWords(it.channel.name).contains(foldWords(q)) }
                }
            }
            val s = words.await()
            val c = channel.await()
            if (query.trim() != q) return@launch
            suggestions = s
            quickHit = c
        }
    }

    fun submit(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        typingJob?.cancel()
        SearchHistory.add(q)
        submitted = q
        load()
    }

    fun pickType(type: SearchType) { if (filters.type != type) { filters = filters.copy(type = type); load() } }
    fun applyFilters(f: SearchFilters) { if (f != filters) { filters = f; load() } }
    fun retry() = load()

    private fun load() {
        val q = submitted.ifBlank { return }
        val f = filters
        loading = true
        error = null
        moreFailed = false
        job?.cancel()
        job = scope.launch {
            runCatching { InnerTube.search(q, f) }
                .onSuccess { page -> hits = page.hits; next = page.next; generation++ }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) { hits = emptyList(); next = null; error = YouTube.friendlyError(it) } else throw it }
            loading = false
        }
    }

    fun loadMore() {
        val token = next ?: return
        if (loading || moreFailed) return
        loading = true
        job = scope.launch {
            runCatching { InnerTube.search(submitted, filters, token) }
                .onSuccess { page ->
                    val seen = hits.mapTo(HashSet()) { it.key }
                    hits = hits + page.hits.filter { seen.add(it.key) }
                    next = page.next
                }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else moreFailed = true }
            loading = false
        }
    }

    fun retryMore() { moreFailed = false; loadMore() }

    private companion object {
        const val SuggestDelayMs = 180L
        const val ChannelWaitMs = 700L
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: SearchState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    scrollToTop: Int = 0,
) {
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    val openChannel = LocalChannelOpener.current
    // Rule 4: Search / Enter / a pick dismiss the keyboard; the window-level hide runs after the key event.
    fun dismissKeyboard() {
        focus.clearFocus(force = true)
        keyboard?.hide()
        view.postDelayed({ view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0) }, 120)
    }
    fun submit(text: String = state.query) { dismissKeyboard(); state.submit(text) }
    var focused by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val tv = LocalWindowLayout.current.tv
    val firstResult = remember { FocusRequester() }
    LaunchedEffect(state.generation) { if (tv && state.generation > 0 && state.hits.isNotEmpty()) firstResult.focusWhenReady() }
    // The keyboard closed by Back or its own button drops the field's focus, so the results come back.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (!imeVisible && focused) focus.clearFocus() }
    val history = SearchHistory.matching(state.query)
    val typing = focused || state.submitted.isEmpty()
    val showSuggestions = typing && (history.isNotEmpty() || state.query.isNotBlank())
    CollapsingHeaderLayout(
        resetKey = Triple(scrollToTop, state.generation, showSuggestions),
        header = {
            Column {
                ScreenHeader(title = stringResource(R.string.ui_search_title))
                SearchField(state, focused, onFocus = { focused = it }, onSubmit = { submit() }, onBack = ::dismissKeyboard, focusSignal = state.focusField)
                Spacer(Modifier.height(8.dp))
            }
        },
    ) { top ->
        Box(Modifier.fillMaxSize()) {
            when {
                showSuggestions -> SuggestionList(
                    state, history, contentPadding.withTop(top),
                    onSearch = { submit(it) },
                    onFill = { state.type(it) },
                    onChannel = { ch -> dismissKeyboard(); openChannel("https://www.youtube.com/channel/${ch.id}") },
                )
                state.loading && state.hits.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = top + 48.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
                }
                state.error != null -> Box(Modifier.padding(top = top)) {
                    EmptyState(
                        icon = Icons.Rounded.CloudOff,
                        title = stringResource(R.string.ui_search_error_title),
                        body = state.error.orEmpty(),
                        action = { PillButton(stringResource(R.string.ui_try_again), { state.retry() }) },
                    )
                }
                state.submitted.isEmpty() -> Box(Modifier.padding(top = top)) {
                    EmptyState(Icons.Rounded.TravelExplore, stringResource(R.string.ui_search_intro_title), stringResource(R.string.ui_search_intro_body))
                }
                else -> Results(state, contentPadding.withTop(top), scrollToTop, onOpen, onOpenPlaylist, firstResult, onFilters = { showFilters = true })
            }
        }
    }
    if (showFilters) SearchFiltersSheet(state.filters, onApply = { state.applyFilters(it); showFilters = false }, onDismiss = { showFilters = false })
}

/** The 60 dp field; while typing over results, its leading icon is a Back arrow that returns to them. */
@Composable
private fun SearchField(state: SearchState, focused: Boolean, onFocus: (Boolean) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit, focusSignal: Int) {
    val c = MaterialTheme.colorScheme
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Once per ask (the search widget): reset, or every later visit to the tab would pop the keyboard again.
    LaunchedEffect(focusSignal) { if (focusSignal > 0) { requester.focusWhenReady(); keyboard?.show(); state.focusField = 0 } }
    val back = focused && state.submitted.isNotEmpty()
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(60.dp)
            .clip(CircleShape)
            .background(c.surfaceContainerHigh)
            .padding(start = if (back) 8.dp else 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (back) {
            Box(Modifier.size(44.dp).pressable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), tint = c.onSurface)
            }
            Spacer(Modifier.width(4.dp))
        } else {
            Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            if (state.query.isEmpty()) {
                Text(stringResource(R.string.ui_search_hint), color = c.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            BasicTextField(
                value = state.query,
                onValueChange = { state.type(it) },
                singleLine = true,
                textStyle = OwnDirectionText.merge(TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }),
                // Hardware Enter doesn't go through the IME action; treat it the same.
                modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { onFocus(it.isFocused) }.onPreviewKeyEvent { e ->
                    if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                        if (e.type == KeyEventType.KeyDown) onSubmit()
                        true
                    } else false
                },
            )
        }
        if (state.query.isNotEmpty()) {
            Box(Modifier.size(44.dp).pressable { state.type("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, stringResource(R.string.ui_clear_search), tint = c.onSurfaceVariant)
            }
        }
    }
}

/**
 * While typing: a channel on top (a subscription that matches at once, else YouTube's top channel for the words),
 * past searches that match, then YouTube's suggestions. Empty field: the recent searches with Clear all.
 */
@Composable
private fun SuggestionList(
    state: SearchState,
    history: List<String>,
    contentPadding: PaddingValues,
    onSearch: (String) -> Unit,
    onFill: (String) -> Unit,
    onChannel: (ChannelRef) -> Unit,
) {
    val blank = state.query.isBlank()
    val typed = state.query.trim()
    val local = state.localChannel()
    val channel: Pair<ChannelRef, String?>? = when {
        blank -> null
        local != null -> local to null
        else -> state.quickHit?.let { h ->
            h.channel to listOfNotNull(h.handle, h.subscribers.takeIf { it > 0 }?.let { subscribersLine(it) }).joinDots().ifBlank { null }
        }
    }
    val shownHistory = if (blank) history else history.take(4)
    val youtube = if (blank) emptyList() else state.suggestions.filter { s -> shownHistory.none { it.equals(s, ignoreCase = true) } }
    LazyColumn(contentPadding = contentPadding, modifier = Modifier.imePadding()) {
        if (blank && history.isNotEmpty()) item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp)) {
                SectionTitle(stringResource(R.string.ui_recent_searches), Modifier.weight(1f).alignByBaseline())
                Box(Modifier.alignByBaseline()) {
                    Text(
                        stringResource(R.string.ui_clear_all), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                        modifier = Modifier.pressable { SearchHistory.clear() }.padding(8.dp),
                    )
                }
            }
        }
        if (channel != null) item(key = "channel-" + channel.first.id) {
            QuickChannel(channel.first, channel.second, Modifier.animateItem()) { onChannel(channel.first) }
        }
        items(shownHistory, key = { "h-$it" }) { q ->
            SuggestionRow(q, typed, history = true, Modifier.animateItem(), onClick = { onSearch(q) }, onTrailing = { SearchHistory.remove(q) })
        }
        items(youtube, key = { "s-$it" }) { q ->
            SuggestionRow(q, typed, history = false, Modifier.animateItem(), onClick = { onSearch(q) }, onTrailing = { onFill("$q ") })
        }
    }
}

@Composable
private fun QuickChannel(channel: ChannelRef, line: String?, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .clip(RoundedCornerShape(22.dp))
            .background(c.surfaceContainerLow)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(channel.avatar, channel.name, size = 44)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(stringResource(R.string.search_channel), line).joinDots(),
                color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** A past search (history icon, ✕ removes it) or a suggestion (search icon, the arrow fills the field). What's typed stays plain, the rest is bold. */
@Composable
private fun SuggestionRow(text: String, typed: String, history: Boolean, modifier: Modifier, onClick: () -> Unit, onTrailing: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val label = remember(text, typed) {
        buildAnnotatedString {
            if (typed.isNotEmpty() && text.startsWith(typed, ignoreCase = true)) {
                append(text.substring(0, typed.length))
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(text.substring(typed.length)) }
            } else append(text)
        }
    }
    Row(
        modifier.fillMaxWidth().pressable(onClick = onClick).height(52.dp).padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (history) Icons.Rounded.History else Icons.Rounded.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(44.dp).pressable(onClick = onTrailing), contentAlignment = Alignment.Center) {
            if (history) Icon(Icons.Rounded.Close, stringResource(R.string.ui_remove_search), tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            else Icon(Icons.Rounded.NorthWest, stringResource(R.string.search_fill, text), tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** One row of the results list: a channel, a playlist, or [columns] videos side by side. */
private sealed interface ResultRow {
    val key: String
    data class Videos(val videos: List<VideoItem>) : ResultRow { override val key get() = "v-" + videos.first().id }
    data class One(val hit: SearchHit) : ResultRow { override val key get() = hit.key }
}

private fun rowsOf(hits: List<SearchHit>, columns: Int): List<ResultRow> {
    val out = ArrayList<ResultRow>()
    val run = ArrayList<VideoItem>()
    fun flush() { run.chunked(columns).forEach { out += ResultRow.Videos(it) }; run.clear() }
    hits.forEach { h ->
        when (h) {
            is SearchHit.Video -> if (!Dismissed.hides(h.video)) run += h.video
            else -> { flush(); out += ResultRow.One(h) }
        }
    }
    flush()
    return out
}

@Composable
private fun Results(
    state: SearchState,
    contentPadding: PaddingValues,
    scrollToTop: Int,
    onOpen: (VideoItem) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    firstResult: FocusRequester,
    onFilters: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val columns = gridColumns()
    val list = rememberScrollToTop(scrollToTop)
    // Fresh results always start at the top (rule 8).
    LaunchedEffect(state.generation) { list.scrollToItem(0) }
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, state.hits.size) { if (nearEnd && state.hits.isNotEmpty()) state.loadMore() }
    // Not remembered: cheap, and it must follow Dismissed (a card hidden with "Not interested").
    val rows = rowsOf(state.hits, columns)
    val openChannel = LocalChannelOpener.current
    LazyColumn(state = list, contentPadding = contentPadding) {
        item(key = "types") { TypeChips(state, onFilters) }
        if (state.filters.refinements > 0) item(key = "active") { ActiveFilters(state) }
        if (rows.isEmpty() && !state.loading) item(key = "none") {
            EmptyState(
                icon = Icons.Rounded.SearchOff,
                title = stringResource(R.string.ui_search_nothing_found),
                body = if (state.filters.refinements > 0 || state.filters.type != SearchType.All) stringResource(R.string.search_no_match_filtered, state.submitted)
                else stringResource(R.string.ui_search_no_match, state.submitted),
                action = if (state.filters != SearchFilters()) { { PillButton(stringResource(R.string.search_clear_filters), { state.applyFilters(SearchFilters()) }) } } else null,
            )
        }
        rows.forEachIndexed { index, row ->
            item(key = row.key) {
                val first = if (index == 0) Modifier.focusRequester(firstResult) else Modifier
                when (row) {
                    is ResultRow.Videos -> if (columns <= 1) SearchVideo(row.videos.first(), first, onOpen) else Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp)) {
                        row.videos.forEachIndexed { i, v -> SearchVideo(v, Modifier.weight(1f).then(if (i == 0) first else Modifier), onOpen) }
                        repeat(columns - row.videos.size) { Spacer(Modifier.weight(1f)) }
                    }
                    is ResultRow.One -> when (val h = row.hit) {
                        is SearchHit.Channel -> ChannelResult(h, first) { openChannel("https://www.youtube.com/channel/${h.channel.id}") }
                        is SearchHit.Playlist -> PlaylistResult(h, first) { onOpenPlaylist(h.playlist) }
                        is SearchHit.Video -> SearchVideo(h.video, first, onOpen)
                    }
                }
            }
        }
        // Unkeyed trailing items (rule 8).
        when {
            state.moreFailed -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.search_more_failed), color = c.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    PillButton(stringResource(R.string.ui_try_again), { state.retryMore() })
                }
            }
            state.loading || state.hasMore -> item {
                Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchVideo(video: VideoItem, modifier: Modifier, onOpen: (VideoItem) -> Unit) {
    SwipeVideo(video, modifier = modifier, inset = VideoCardInset, corner = VideoCardCorner) {
        VideoCard(video, onClick = { onOpen(video) })
    }
}

/** All · Videos · Channels · Playlists, then Filters (its count when set). A row that scrolls (0.5.65). */
@Composable
private fun TypeChips(state: SearchState, onFilters: () -> Unit) {
    val c = MaterialTheme.colorScheme
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        items(SearchType.entries) { t -> FilterPill(stringResource(t.labelRes), selected = state.filters.type == t) { state.pickType(t) } }
        item {
            val n = state.filters.refinements
            Row(
                Modifier.height(36.dp).pressable(onClick = onFilters).clip(RoundedCornerShape(18.dp))
                    .background(if (n > 0) c.primaryContainer else c.surfaceContainerHigh).padding(start = 12.dp, end = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ink = if (n > 0) c.onPrimaryContainer else c.onSurfaceVariant
                Icon(Icons.Rounded.Tune, null, tint = ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(listOfNotNull(stringResource(R.string.search_filters), n.takeIf { it > 0 }?.toString()).joinDots(), color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

/** The filters that are set, each removable on its own (and the search runs again). */
@Composable
private fun ActiveFilters(state: SearchState) {
    val f = state.filters
    // What was sent: Channels / Playlists searches carry no date, length or order.
    val sent = f.effective
    val chips = buildList {
        if (sent.date != SearchDate.Any) add(stringResource(f.date.labelRes) to f.copy(date = SearchDate.Any))
        if (sent.length != SearchLength.Any) add(stringResource(f.length.labelRes) to f.copy(length = SearchLength.Any))
        if (sent.sort != SearchSort.Relevance) add(stringResource(f.sort.labelRes) to f.copy(sort = SearchSort.Relevance))
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        items(chips, key = { it.first }) { (label, without) ->
            Box(Modifier.animateItem()) { RemovableChip(label, null, stringResource(R.string.search_remove_filter, label)) { state.applyFilters(without) } }
        }
    }
}

@Composable
private fun ChannelResult(hit: SearchHit.Channel, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .clip(RoundedCornerShape(22.dp))
            .background(c.surfaceContainerLow)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelAvatar(hit.channel.avatar, hit.channel.name, size = 52)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.channel.name, fontWeight = FontWeight.ExtraBold, fontSize = 15.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(hit.handle, hit.subscribers.takeIf { it > 0 }?.let { subscribersLine(it) }).joinDots()
            if (line.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(line, color = c.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PlaylistResult(hit: SearchHit.Playlist, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().pressable(pressedScale = 0.97f, onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(VideoRowCorner))) {
            Thumbnail(hit.playlist.thumbnail, Modifier.fillMaxSize(), radius = 0)
            Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.42f).background(Color.Black.copy(alpha = 0.65f)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(18.dp))
                hit.playlist.subtitle?.let { count ->
                    Text(count, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 13.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.playlist.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(stringResource(R.string.search_playlist), hit.channel).joinDots(),
                color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Upload date, length and order (YouTube's own choices); they apply to videos, so they dim for channels / playlists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchFiltersSheet(current: SearchFilters, onApply: (SearchFilters) -> Unit, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var f by remember { mutableStateOf(current) }
    val videosOnly = f.type == SearchType.Channels || f.type == SearchType.Playlists
    val dim by animateFloatAsState(if (videosOnly) 0.4f else 1f, tween(200), label = "filtersDim")
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.search_filters), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.search_reset), color = c.primary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                    modifier = Modifier.pressable { f = SearchFilters(type = f.type) }.padding(8.dp),
                )
            }
            if (videosOnly) Text(stringResource(R.string.search_videos_only_note), color = c.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Column(Modifier.graphicsLayer { alpha = dim }) {
                SheetLabel(stringResource(R.string.search_date))
                ChoiceRow(SearchDate.entries.map { it to stringResource(it.labelRes) }, f.date, !videosOnly) { f = f.copy(date = it) }
                SheetLabel(stringResource(R.string.search_length))
                ChoiceRow(SearchLength.entries.map { it to stringResource(it.labelRes) }, f.length, !videosOnly) { f = f.copy(length = it) }
                SheetLabel(stringResource(R.string.search_sort))
                ChoiceRow(SearchSort.entries.map { it to stringResource(it.labelRes) }, f.sort, !videosOnly) { f = f.copy(sort = it) }
            }
            Box(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp).fillMaxWidth().height(48.dp)
                    .pressable { onApply(f) }.clip(CircleShape).background(c.primary),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.search_apply), color = c.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, enabled: Boolean, onPick: (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { (value, label) -> FilterPill(label, selected = value == selected) { if (enabled) onPick(value) } }
    }
}
