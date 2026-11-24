package com.decio.skipii

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ── Home / Subscriptions / Library ─────────────────────────────────────────────
// Signed in, these show the account's real feeds. Signed out, they explain what signing
// in brings rather than showing sample content.

@Composable
fun HomeScreen(
    lib: LibraryData,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onSearch: () -> Unit,
    onSignIn: () -> Unit,
    onAccount: () -> Unit,
    scrollToTop: Int = 0,
    /** Wide windows (Rail 3): Shorts as one compact card in the grid, never a shelf. */
    shortsCard: (@Composable (Modifier) -> Unit)? = null,
    /** A subscriptions' Shorts row tile (#96): the row plays in the Shorts pager from it. */
    onOpenShorts: (ShortsList) -> Unit = {},
) {
    val signedIn = Account.signedIn
    LaunchedEffect(signedIn) { if (signedIn) lib.home.ensureLoaded() }
    // "Shorts from subscriptions" on Home: the row comes from the Subscriptions feed (read ahead by warmUp);
    // a Home refresh also refreshes it once it's over 10 min old.
    val subsShorts = FeedFilters.subscriptionShorts
    LaunchedEffect(signedIn, subsShorts, lib.home.generation) {
        if (signedIn && subsShorts) lib.subscriptionsForHome()
    }
    var showFilters by remember { mutableStateOf(false) }
    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.home.generation,
        header = {
            ScreenHeader(title = "Skipii", brandMark = true) {
                UpdateAction()
                if (signedIn) FeedFilterAction { showFilters = true }
                AccountAction(onAccount)
            }
        },
    ) { top ->
        if (signedIn) {
            val home = lib.home
            FeedList(home, contentPadding.withTop(top), onOpen, videos = FeedFilters.apply(home.videos), filtered = FeedFilters.active,
                filteredEmpty = { more ->
                    FilteredOut(stringResource(R.string.feed_filtered_out_title), stringResource(R.string.feed_filtered_out_body), stringResource(R.string.feed_edit_filters), { showFilters = true }, more)
                },
                header = {
                // Always present (empty until YouTube sends chips) so their arrival never shifts the list.
                item(key = "chips") { if (home.chips.isNotEmpty()) HomeChips(home.chips, home.chip, home::selectChip) }
                // Only on the plain feed ("All"), never under a topic chip.
                val subShorts = lib.subscriptions.shorts
                if (subsShorts && home.chip?.token == null && subShorts.isNotEmpty()) item(key = "subShorts") {
                    SubscriptionShortsRow(subShorts) { i -> onOpenShorts(ShortsList(subShorts, i)) }
                }
                // A chip whose page held no videos: say so, never a blank page.
                val chosen = home.chip
                val plain = home.chips.firstOrNull { it.token == null }
                if (chosen != null && home.loadedEmpty) item(key = "chipEmpty") {
                    EmptyState(
                        icon = Icons.Rounded.VideoLibrary,
                        title = stringResource(R.string.ui_chip_empty_title),
                        body = stringResource(R.string.ui_chip_empty_body, chosen.title),
                        action = plain?.let { { PillButton(it.title, { home.selectChip(it) }) } },
                    )
                }
            }, scrollToTop = scrollToTop, indicatorTop = top, gridExtra = shortsCard)
            if (showFilters) FeedFiltersSheet(hidden = home.videos.count(FeedFilters::hides), onDismiss = { showFilters = false })
        } else {
            LazyColumn(contentPadding = contentPadding.withTop(top)) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Subscriptions,
                        title = stringResource(R.string.ui_home_signed_out_title),
                        body = stringResource(R.string.ui_home_signed_out_body),
                        action = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PillButton(stringResource(R.string.ui_sign_in_with_google), onSignIn)
                                Spacer(Modifier.height(12.dp))
                                TextLink(stringResource(R.string.ui_search_instead), onSearch)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SubscriptionsScreen(
    lib: LibraryData,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onSignIn: () -> Unit,
    onAccount: () -> Unit,
    scrollToTop: Int = 0,
    /** A Shorts row tile (#96): the row plays in the Shorts pager from it. */
    onOpenShorts: (ShortsList) -> Unit = {},
) {
    val signedIn = Account.signedIn
    LaunchedEffect(signedIn) {
        if (signedIn) lib.showSubscriptions()
    }
    var showFilters by remember { mutableStateOf(false) }
    // The group editor: null closed; [editingGroup] null with it open = a new group.
    var editorOpen by remember { mutableStateOf(false) }
    var editingGroup by remember { mutableStateOf<SubscriptionGroup?>(null) }
    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.subscriptions.generation,
        header = {
            ScreenHeader(title = stringResource(R.string.ui_subscriptions_title)) {
                if (signedIn) FeedFilterAction { showFilters = true }
                AccountAction(onAccount)
            }
        },
    ) { top ->
        if (signedIn) {
            val subs = lib.subscriptions
            val group = SubscriptionGroups.current
            val narrowed = FeedFilters.active || SubscriptionGroups.narrowing
            FeedList(
                subs, contentPadding.withTop(top), onOpen,
                videos = SubscriptionGroups.apply(FeedFilters.apply(subs.videos)),
                filtered = narrowed,
                scrollKey = SubscriptionGroups.selected to SubscriptionGroups.unwatchedOnly,
                filteredEmpty = { more ->
                    if (group != null) FilteredOut(
                        stringResource(R.string.feed_group_empty_title, group.name), stringResource(R.string.feed_group_empty_body),
                        stringResource(R.string.feed_group_edit), { editingGroup = group; editorOpen = true }, more,
                    ) else FilteredOut(
                        stringResource(R.string.feed_filtered_out_title), stringResource(R.string.feed_filtered_out_body),
                        stringResource(R.string.feed_edit_filters), { showFilters = true }, more,
                    )
                },
                header = {
                    item(key = "groups") { SubscriptionGroupChips(onEdit = { editingGroup = it; editorOpen = true }) }
                    // Always present (empty until loaded) so its arrival never shifts the list; the
                    // row kept from last time shows from the first frame (LibraryData).
                    item(key = "channels") {
                        val shown = if (group == null) lib.channels else lib.channels.filter { it.id in group.channels }
                        if (shown.isNotEmpty()) ChannelRail(shown)
                    }
                    item(key = "unwatched") { UnwatchedChip() }
                    if (FeedFilters.subscriptionShorts && subs.shorts.isNotEmpty()) item(key = "shorts") {
                        SubscriptionShortsRow(subs.shorts) { i -> onOpenShorts(ShortsList(subs.shorts, i)) }
                    }
                },
                scrollToTop = scrollToTop, indicatorTop = top,
            )
            if (showFilters) FeedFiltersSheet(hidden = subs.videos.count(FeedFilters::hides), onDismiss = { showFilters = false })
            if (editorOpen) GroupEditorSheet(editingGroup, lib.channels, onDismiss = { editorOpen = false })
        } else {
            LazyColumn(contentPadding = contentPadding.withTop(top)) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Subscriptions,
                        title = stringResource(R.string.ui_subscriptions_signed_out_title),
                        body = stringResource(R.string.ui_subscriptions_signed_out_body),
                        action = { PillButton(stringResource(R.string.ui_sign_in_with_google), onSignIn) },
                    )
                }
            }
        }
    }
}

/**
 * Home's category chips as YouTube sent them ("All", "Music", "Gaming"…): plain words in a row
 * that scrolls (Derek picked B "Underline" in docs/design/home-chips.html). The chosen word is
 * primary and heavier, and a short rounded bar springs under it from the last pick. The row
 * fades out at whichever edge has more to scroll to.
 */
@Composable
private fun HomeChips(chips: List<FeedChip>, chosen: FeedChip?, onPick: (FeedChip) -> Unit) {
    val selected = chips.indexOfFirst { chosen?.let { ch -> ch.title == it.title } ?: (it.token == null) }
    UnderlineTabs(chips.map { it.title }, selected, { onPick(chips[it]) }, Modifier.padding(top = 2.dp, bottom = 10.dp))
}

/**
 * Words in a row that scrolls, the [selected] one primary and heavier with a short rounded bar
 * springing under it from the last pick; the row fades out at whichever edge has more to scroll
 * to. Home's categories and a channel's tabs.
 */
@Composable
fun UnderlineTabs(labels: List<String>, selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    // Each word's left edge and width in the row, in px (written as they're placed).
    val spots = remember(labels) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val barLeft = remember { Animatable(0f) }
    val barWidth = remember { Animatable(0f) }
    var barShown by remember { mutableStateOf(false) }
    val target = spots[selected]
    LaunchedEffect(target) {
        val (left, width) = target ?: return@LaunchedEffect
        val w = maxOf(width * ChipBarShare, with(density) { ChipBarMin.toPx() })
        val l = left + (width - w) / 2
        if (!barShown || reduced) {
            barLeft.snapTo(l); barWidth.snapTo(w); barShown = true
            return@LaunchedEffect
        }
        val spec = spring<Float>(dampingRatio = 0.62f, stiffness = 450f)
        launch { barLeft.animateTo(l, spec) }
        barWidth.animateTo(w, spec)
    }
    // A pick near an edge scrolls the row to bring it to the middle.
    LaunchedEffect(selected) {
        val (left, width) = spots[selected] ?: return@LaunchedEffect
        val edge = with(density) { ChipRowPadding.toPx() }
        val x = left + edge
        val view = scroll.viewportSize
        if (x - edge < scroll.value || x + width + edge > scroll.value + view) {
            val to = (x + width / 2 - view / 2f).toInt()
            if (reduced) scroll.scrollTo(to) else scroll.animateScrollTo(to)
        }
    }
    val fade = with(density) { ChipRowFade.toPx() }
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // Fade only where there's more row to scroll to.
                val leftFade = (scroll.value / fade).coerceIn(0f, 1f)
                val rightFade = ((scroll.maxValue - scroll.value) / fade).coerceIn(0f, 1f)
                val w = size.width
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 1f - leftFade),
                        (fade / w) to Color.Black,
                        (1f - fade / w) to Color.Black,
                        1f to Color.Black.copy(alpha = 1f - rightFade),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Row(
            Modifier
                .horizontalScroll(scroll)
                .padding(horizontal = ChipRowPadding)
                .drawBehind {
                    if (!barShown) return@drawBehind
                    val h = ChipBarHeight.toPx()
                    drawRoundRect(
                        c.primary,
                        topLeft = Offset(barLeft.value, size.height - h),
                        size = Size(barWidth.value, h),
                        cornerRadius = CornerRadius(h / 2),
                    )
                },
        ) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                val pad = with(density) { ChipWordPadding.toPx() }
                val fg by animateColorAsState(if (on) c.primary else c.onSurfaceVariant, tween(if (reduced) 0 else 220), label = "chipFg")
                Box(
                    Modifier
                        .onPlaced { spots[i] = it.positionInParent().x + pad to it.size.width - 2 * pad }
                        .pressable(onClick = { onPick(i) })
                        .height(ChipRowHeight)
                        .padding(horizontal = ChipWordPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = fg,
                        fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** The row ([UnderlineTabs]): 20 dp in from the edges, words 22 dp apart (11 dp of tap room each side). */
private val ChipRowPadding = 9.dp
private val ChipWordPadding = 11.dp
private val ChipRowHeight = 40.dp
private val ChipRowFade = 28.dp
/** The bar under the chosen word: 60% of the word, never under 18 dp, 4 dp tall. */
private const val ChipBarShare = 0.6f
private val ChipBarMin = 18.dp
private val ChipBarHeight = 4.dp

@Composable
private fun ChannelRail(channels: List<ChannelRef>) {
    val c = MaterialTheme.colorScheme
    val openChannel = LocalChannelOpener.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
    ) {
        items(channels, key = { it.id }) { ch ->
            Column(
                Modifier.width(64.dp).pressable { openChannel("https://www.youtube.com/channel/${ch.id}") },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ChannelAvatar(ch.avatar, ch.name, size = 56)
                Spacer(Modifier.height(6.dp))
                Text(ch.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun LibraryScreen(
    lib: LibraryData,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    onSignIn: () -> Unit,
    onAccount: () -> Unit,
    onOpenHistory: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    scrollToTop: Int = 0,
) {
    val signedIn = Account.signedIn
    LaunchedEffect(signedIn) {
        if (signedIn) {
            lib.history.ensureLoaded()
            if (lib.playlists.isEmpty()) lib.loadPlaylists()
        }
    }
    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.history.generation,
        header = { ScreenHeader(title = stringResource(R.string.ui_library_title)) { AccountAction(onAccount) } },
    ) { top ->
        PullToRefresh(
            enabled = signedIn,
            refreshing = lib.history.refreshing,
            onRefresh = { lib.history.refresh(); lib.loadPlaylists() },
            topPadding = top,
        ) {
        // Resolved here: the list's builder below isn't composable.
        val watchLater = stringResource(R.string.ui_watch_later)
        val likedVideos = stringResource(R.string.ui_liked_videos)
        val history = lib.history.videos
        val unfinished = history.filter { ResumeStore.progress(it.id) != null }
        val showContinue = unfinished.isNotEmpty()
        LazyColumn(state = rememberScrollToTop(scrollToTop), contentPadding = contentPadding.withTop(top)) {
            if (!signedIn) {
                // Downloads work signed out too: on the phone, kept above the sign-in pitch.
                item(key = "downloads") { DownloadsTile(onOpenDownloads, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) }
                item {
                    EmptyState(
                        icon = Icons.Rounded.VideoLibrary,
                        title = stringResource(R.string.ui_library_signed_out_title),
                        body = stringResource(R.string.ui_library_signed_out_body),
                        action = { PillButton(stringResource(R.string.ui_sign_in_with_google), onSignIn) },
                    )
                }
                return@LazyColumn
            }
            item(key = "historyTitle") {
                LibrarySectionHeader(
                    title = stringResource(if (showContinue) R.string.ui_continue_watching else R.string.ui_history),
                    actionLabel = if (history.isNotEmpty()) stringResource(if (showContinue) R.string.ui_history else R.string.ui_see_all) else null,
                    onAction = onOpenHistory,
                )
            }
            item(key = "history") {
                when {
                    showContinue -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(unfinished.take(15), key = { "cw-" + it.id }) { video ->
                            ContinueWatchingCard(video) { onOpen(video) }
                        }
                    }
                    history.isNotEmpty() -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(history.take(20), key = { "h-" + it.id }) { video -> HistoryCard(video) { onOpen(video) } }
                    }
                    lib.history.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp, modifier = Modifier.size(28.dp))
                    }
                    else -> Text(
                        lib.history.error ?: stringResource(R.string.ui_history_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            item(key = "pinnedLists") {
                // Watch later + Liked videos as the pre-0.5.118 tiles (Derek, 2026-11-20: back from the joined bar).
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(PlaylistRef("WL", watchLater, null, null), PlaylistRef("LL", likedVideos, null, null)).forEach { pl ->
                        PinnedListTile(pl, Modifier.weight(1f)) { onOpenPlaylist(pl) }
                    }
                }
            }
            item(key = "downloads") { DownloadsTile(onOpenDownloads, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp)) }
            if (lib.playlists.isNotEmpty()) {
                item(key = "playlistsTitle") { SectionTitle(stringResource(R.string.ui_playlists)) }
                items(lib.playlists.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { pl -> PlaylistTile(pl, Modifier.weight(1f)) { onOpenPlaylist(pl) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun LibrarySectionHeader(title: String, actionLabel: String?, onAction: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontFamily = RoundedHeavy,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 21.sp,
            letterSpacing = (-0.6).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null) {
            Row(
                Modifier
                    .pressable(onClick = onAction)
                    .clip(CircleShape)
                    .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionLabel, color = c.primary, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = c.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(video: VideoItem, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val menu = LocalVideoMenu.current
    val remaining = ResumeStore.remainingMs(video.id)?.let(::compactDurationFromMs)?.let { formatRemainingLabel(it) }
    Column(
        Modifier
            .width(178.dp)
            .prefetchOnPress(video.id)
            .pressable(pressedScale = 0.97f, onLongClick = { menu(video) }, onClick = onClick),
    ) {
        Box {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 20)
            if (remaining != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(remaining, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.height(7.dp))
        Text(video.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (video.channel.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(video.channel, color = c.onSurfaceVariant, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun formatRemainingLabel(parts: CompactDuration): String = when {
    parts.underOneMinute -> stringResource(R.string.ui_remaining_under_min)
    parts.hours > 0 && parts.minutes > 0 -> stringResource(R.string.ui_remaining_hr_min, parts.hours, parts.minutes)
    parts.hours > 0 -> stringResource(R.string.ui_remaining_hr, parts.hours)
    else -> stringResource(R.string.ui_remaining_min, parts.minutes)
}

/** Joined tonal quick-bar for Watch later and Liked videos (outer 24 dp, inner 10 dp corners). */
/** Watch later / Liked videos: a 132 dp tile, its icon in a cookie badge, the title at the bottom (as before 0.5.118). */
@Composable
private fun PinnedListTile(playlist: PlaylistRef, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    // Bold on every theme (Derek, 7641.png: on surfaceContainerLow with a pale badge they blended into a light page):
    // the tile takes its container tone, the badge the full colour.
    val wl = playlist.id == "WL"
    val icon = if (wl) Icons.Rounded.WatchLater else Icons.Rounded.ThumbUp
    val tile = if (wl) c.primaryContainer else c.tertiaryContainer
    val ink = if (wl) c.onPrimaryContainer else c.onTertiaryContainer
    val badge = if (wl) c.primary else c.tertiary
    val badgeInk = if (wl) c.onPrimary else c.onTertiary
    Column(
        modifier = modifier
            .height(132.dp)
            .pressable(pressedScale = 0.97f, onClick = onClick)
            .clip(RoundedCornerShape(26.dp))
            .background(tile)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.size(52.dp).clip(CookieShape).background(badge), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = badgeInk)
        }
        Text(playlist.title, color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HistoryCard(video: VideoItem, onClick: () -> Unit) {
    val menu = LocalVideoMenu.current
    Column(Modifier.width(168.dp).prefetchOnPress(video.id).pressable(pressedScale = 0.97f, onLongClick = { menu(video) }, onClick = onClick)) {
        Box {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 20)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(6.dp), small = true)
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.height(7.dp))
        Text(video.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Full-bleed cover playlist tile: edge-to-edge 16:9 artwork inside a 24 dp tonal card. */
@Composable
private fun PlaylistTile(playlist: PlaylistRef, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val (icon, tone) = when (playlist.id) {
        "WL" -> Icons.Rounded.WatchLater to c.primaryContainer
        "LL" -> Icons.Rounded.ThumbUp to c.tertiaryContainer
        else -> Icons.AutoMirrored.Rounded.PlaylistPlay to c.secondaryContainer
    }
    Column(
        modifier = modifier
            .pressable(pressedScale = 0.97f, onClick = onClick)
            .clip(shape)
            .background(c.surfaceContainerLow),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(tone)) {
            if (playlist.thumbnail != null) {
                Thumbnail(playlist.thumbnail, Modifier.fillMaxSize(), radius = 0)
            } else {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(48.dp)
                        .clip(CookieShape)
                        .background(c.surface.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = contentColorFor(tone))
                }
            }
            playlist.subtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(sub, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(playlist.title, fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Full scrollable watch history page opened from Library's "History ›" header link. */
@Composable
fun HistoryScreen(
    feed: FeedState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpen: (VideoItem) -> Unit,
) {
    LaunchedEffect(Unit) { feed.ensureLoaded() }
    // YouTube's own history search (recent history only, said under the field): applied 400 ms after typing
    // stops, or at once on the keyboard's Search.
    var query by rememberSaveable { mutableStateOf("") }
    var applied by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isNotBlank()) kotlinx.coroutines.delay(400)
        applied = query.trim()
    }
    val scope = rememberCoroutineScope()
    val searchFeed = remember(applied) {
        if (applied.isEmpty()) null else FeedState(scope) { InnerTube.historySearch(applied, it) }
    }
    LaunchedEffect(searchFeed) { searchFeed?.ensureLoaded() }
    val shown = searchFeed ?: feed
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CollapsingHeaderLayout(
            resetKey = feed.generation,
            header = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), onBack)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.ui_history),
                        fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.8).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            },
        ) { top ->
            if (feed.videos.isEmpty() && !feed.loading && feed.error == null && !feed.hasMore) {
                Box(Modifier.padding(top = top)) {
                    EmptyState(Icons.Rounded.History, stringResource(R.string.ui_history), stringResource(R.string.ui_history_empty))
                }
            } else {
                FeedList(
                    shown, contentPadding.withTop(top), onOpen, indicatorTop = top,
                    header = {
                        item(key = "search") {
                            ListSearchField(
                                query, { query = it }, stringResource(R.string.ui_search_history),
                                onSubmit = { applied = query.trim() },
                            )
                        }
                        if (query.isNotBlank()) item(key = "search-note") { ListSearchNote(stringResource(R.string.ui_search_history_note)) }
                    },
                    scrollKey = applied,
                    footer = {
                        if (searchFeed != null && searchFeed.loadedEmpty) item(key = "search-empty") {
                            ListSearchEmpty(stringResource(R.string.ui_search_history_no_matches, applied))
                        }
                    },
                )
            }
        }
    }
}

/** A playlist's videos (Watch later, Liked, or the user's own). */
@Composable
fun PlaylistScreen(
    playlist: PlaylistRef, feed: FeedState, contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (VideoItem) -> Unit,
    /** Play all (false) / Shuffle (true): the loaded videos, as the queue (#81). */
    onPlayAll: (shuffle: Boolean) -> Unit = {},
    /** The account's own list: a card swiped left comes out (#85). */
    onRemove: ((VideoItem) -> Unit)? = null,
    /** The account's own list: a long-press opens the sheet with "Remove from …" (#65). */
    onOptions: ((VideoItem) -> Unit)? = null,
) {
    LaunchedEffect(playlist.id) { feed.ensureLoaded() }
    val searchable = playlist.id == "WL" || playlist.id == "LL"
    var query by rememberSaveable(playlist.id) { mutableStateOf("") }
    val menu = LocalVideoMenu.current
    CompositionLocalProvider(LocalVideoMenu provides (onOptions ?: menu)) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    CollapsingHeaderLayout(
        resetKey = feed.generation,
        header = {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                playlist.title,
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.8).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        },
    ) { top ->
        if (feed.videos.isEmpty() && !feed.loading && feed.error == null && !feed.hasMore) {
            Box(Modifier.padding(top = top)) { EmptyState(Icons.Rounded.VideoLibrary, stringResource(R.string.ui_playlist_empty_title), stringResource(R.string.ui_playlist_empty_body)) }
        } else if (searchable) {
            // Watch later / Liked videos aren't really playlists (Derek): no Play all / Shuffle, a search instead,
            // over every video in the list — the first search pages it to its end (FeedState.loadAll).
            val words = remember(query) { queryWords(query) }
            // Watch later only (0.6.0): by length, without what's been watched, and "Pick one for me".
            val wl = playlist.id == "WL"
            var length by rememberSaveable(playlist.id) { mutableStateOf(WatchLaterLength.Any) }
            var hideWatched by rememberSaveable(playlist.id) { mutableStateOf(false) }
            val narrowed = wl && (length != WatchLaterLength.Any || hideWatched)
            val searching = words.isNotEmpty() || narrowed
            LaunchedEffect(searching) { if (searching) feed.loadAll() }
            val index = remember(feed) { ListSearchIndex() }
            val found = remember(feed.videos, words) { index.filter(feed.videos, words) }
            val matches = if (!narrowed) found else found.filter { v ->
                length.fits(v.durationSec) && (!hideWatched || !WatchedStore.watched(v.id))
            }
            val current = LocalCurrentVideo.current
            val stillLoading = feed.loading || (feed.hasMore && feed.error == null)
            FeedList(
                feed, contentPadding.withTop(top), onOpen, indicatorTop = top,
                header = {
                    item(key = "search") {
                        ListSearchField(query, { query = it }, stringResource(R.string.ui_search_in_list, playlist.title))
                    }
                    if (wl) item(key = "wlLength") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            items(WatchLaterLength.entries) { l -> FilterPill(stringResource(l.labelRes), selected = length == l) { length = l } }
                        }
                    }
                    if (wl) item(key = "wlMeta") {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            HideWatchedChip(hideWatched) { hideWatched = !hideWatched }
                            Spacer(Modifier.weight(1f))
                            if (narrowed) Text(
                                if (feed.loading || feed.hasMore) pluralStringResource(R.plurals.feed_count_so_far, matches.size, wholeCount(matches.size))
                                else pluralStringResource(R.plurals.feed_count, matches.size, wholeCount(matches.size)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            )
                        }
                    }
                    if (wl && feed.videos.isNotEmpty()) item(key = "wlPick") {
                        PickOneButton(enabled = matches.isNotEmpty()) {
                            val pool = matches.filter { it.id != current }.ifEmpty { matches }
                            pool.randomOrNull()?.let(onOpen)
                        }
                    }
                    if (words.isNotEmpty() && !narrowed && (matches.isNotEmpty() || stillLoading)) item(key = "search-note") {
                        ListSearchNote(
                            if (stillLoading) stringResource(R.string.ui_search_found_so_far, wholeCount(matches.size))
                            else stringResource(R.string.ui_search_count_of, wholeCount(matches.size), wholeCount(feed.videos.size)),
                        )
                    }
                },
                onSwipeAway = onRemove,
                videos = matches,
                scrollKey = Triple(words, length, hideWatched),
                showLoader = !(searching && feed.error != null && feed.hasMore),
                footer = {
                    if (searching && feed.error != null && feed.hasMore) item(key = "search-error") {
                        EmptyState(
                            icon = Icons.Rounded.CloudOff,
                            title = stringResource(R.string.ui_search_load_failed, playlist.title),
                            body = feed.error.orEmpty(),
                            action = { PillButton(stringResource(R.string.ui_try_again), { feed.loadAll() }) },
                        )
                    } else if (searching && matches.isEmpty() && !stillLoading) item(key = "search-empty") {
                        ListSearchEmpty(
                            if (words.isEmpty()) stringResource(R.string.feed_none_match)
                            else stringResource(R.string.ui_search_no_matches_in, playlist.title, query.trim()),
                        )
                    }
                },
            )
        } else {
            FeedList(
                feed, contentPadding.withTop(top), onOpen, indicatorTop = top,
                header = {
                    if (feed.videos.isNotEmpty()) item(key = "play-all") { PlayAllRow(onPlayAll) }
                },
                onSwipeAway = onRemove,
            )
        }
    }
    }
    }
}

/** Watch later's length chips (0.6.0). Unknown lengths (0) count as any. */
enum class WatchLaterLength(@androidx.annotation.StringRes val labelRes: Int, private val min: Long, private val max: Long) {
    Any(R.string.feed_wl_any, 0, Long.MAX_VALUE),
    Under10(R.string.feed_wl_under10, 0, 600),
    Ten30(R.string.feed_wl_10to30, 600, 1800),
    Over30(R.string.feed_wl_over30, 1800, Long.MAX_VALUE);

    fun fits(seconds: Long) = this == Any || (seconds > 0 && seconds >= min && seconds < max)
}

/** The video open in the player (Watch later's "Pick one for me" skips it). */
val LocalCurrentVideo = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

@Composable
private fun HideWatchedChip(on: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (on) c.primaryContainer else c.surfaceContainerHigh, tween(200), label = "hideWatchedBg")
    val ink by animateColorAsState(if (on) c.onPrimaryContainer else c.onSurfaceVariant, tween(200), label = "hideWatchedInk")
    Row(
        Modifier.height(34.dp).pressable(onClick = onClick).clip(CircleShape).background(bg).padding(start = if (on) 10.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) { Icon(Icons.Rounded.Check, null, tint = ink, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)) }
        Text(stringResource(R.string.feed_hide_watched_chip), color = ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

/** A full-width primary "Pick one for me" (a random video among what's shown). */
@Composable
private fun PickOneButton(enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp).fillMaxWidth().height(46.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .clip(CircleShape).background(c.primary),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Shuffle, null, tint = c.onPrimary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.feed_pick_one), color = c.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** A playlist's Play all (full primary) and Shuffle (tonal) pills, over its videos (#81). */
@Composable
private fun PlayAllRow(onPlayAll: (shuffle: Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false, true).forEach { shuffle ->
            val bg = if (shuffle) c.surfaceContainerHigh else c.primary
            val ink = if (shuffle) c.onSurface else c.onPrimary
            Row(
                Modifier.pressable { onPlayAll(shuffle) }.height(44.dp).clip(CircleShape).background(bg)
                    .padding(start = 14.dp, end = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (shuffle) Icons.Rounded.Shuffle else Icons.Rounded.PlayArrow, null, tint = ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(if (shuffle) R.string.ui_shuffle else R.string.ui_play_all),
                    color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 14.sp,
        modifier = Modifier.pressable(onClick = onClick).padding(8.dp),
    )
}
