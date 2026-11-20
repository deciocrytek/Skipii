package com.decio.skipii

import androidx.compose.runtime.CompositionLocalProvider
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
) {
    val signedIn = Account.signedIn
    LaunchedEffect(signedIn) { if (signedIn) lib.home.ensureLoaded() }
    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.home.generation,
        header = { ScreenHeader(title = "Skipii", brandMark = true) { UpdateAction(); AccountAction(onAccount) } },
    ) { top ->
        if (signedIn) {
            val home = lib.home
            FeedList(home, contentPadding.withTop(top), onOpen, header = {
                // Always present (empty until YouTube sends chips) so their arrival never shifts the list.
                item(key = "chips") { if (home.chips.isNotEmpty()) HomeChips(home.chips, home.chip, home::selectChip) }
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
) {
    val signedIn = Account.signedIn
    LaunchedEffect(signedIn) {
        if (signedIn) lib.showSubscriptions()
    }
    CollapsingHeaderLayout(
        resetKey = scrollToTop to lib.subscriptions.generation,
        header = { ScreenHeader(title = stringResource(R.string.ui_subscriptions_title)) { AccountAction(onAccount) } },
    ) { top ->
        if (signedIn) {
            FeedList(lib.subscriptions, contentPadding.withTop(top), onOpen, header = {
                // Always present (empty until loaded) so its arrival never shifts the list; the
                // row kept from last time shows from the first frame (LibraryData).
                item(key = "channels") { if (lib.channels.isNotEmpty()) ChannelRail(lib.channels) }
            }, scrollToTop = scrollToTop, indicatorTop = top)
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
            item(key = "quickBar") {
                LibraryQuickBar(
                    watchLater = PlaylistRef("WL", watchLater, null, null),
                    likedVideos = PlaylistRef("LL", likedVideos, null, null),
                    onOpenPlaylist = onOpenPlaylist,
                )
            }
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
@Composable
private fun LibraryQuickBar(
    watchLater: PlaylistRef,
    likedVideos: PlaylistRef,
    onOpenPlaylist: (PlaylistRef) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val leftShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 10.dp, bottomEnd = 10.dp)
        val rightShape = RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp, topEnd = 24.dp, bottomEnd = 24.dp)
        Row(
            Modifier
                .weight(1f)
                .height(56.dp)
                .pressable(pressedScale = 0.97f) { onOpenPlaylist(watchLater) }
                .clip(leftShape)
                .background(c.surfaceContainerHigh)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.WatchLater, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(watchLater.title, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier
                .weight(1f)
                .height(56.dp)
                .pressable(pressedScale = 0.97f) { onOpenPlaylist(likedVideos) }
                .clip(rightShape)
                .background(c.surfaceContainerHigh)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.ThumbUp, contentDescription = null, tint = c.tertiary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(likedVideos.title, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
                FeedList(feed, contentPadding.withTop(top), onOpen, indicatorTop = top)
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

// ── Search ─────────────────────────────────────────────────────────────────────

/** Lives above the tab switcher so results survive switching tabs. */
@Stable
class SearchState(private val scope: CoroutineScope) {
    var query by mutableStateOf("")
    var submitted by mutableStateOf("")
        private set
    var results by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var generation by mutableStateOf(0)
        private set
    private var job: Job? = null

    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        SearchHistory.add(q)
        submitted = q
        loading = true
        error = null
        job?.cancel()
        job = scope.launch {
            runCatching { YouTube.search(q) }
                .onSuccess { results = it; generation++ }
                .onFailure { error = YouTube.friendlyError(it) }
            loading = false
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(state: SearchState, contentPadding: PaddingValues, onOpen: (VideoItem) -> Unit, scrollToTop: Int = 0) {
    val c = MaterialTheme.colorScheme
    val columns = gridColumns()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    // Search and Enter both dismiss the keyboard — every field, every time. A hide issued
    // while the key event is still being dispatched can be undone by the IME, so the
    // window-level hide is posted to run after it.
    fun submit() {
        focus.clearFocus(force = true)
        keyboard?.hide()
        view.postDelayed({
            view.context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }, 120)
        state.submit()
    }
    var focused by remember { mutableStateOf(false) }
    // On a TV the remote lands on the first result once they're in (else it would fall back to the rail).
    val tv = LocalWindowLayout.current.tv
    val firstResult = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(state.generation) { if (tv && state.generation > 0 && state.results.isNotEmpty()) firstResult.focusWhenReady() }
    // The keyboard closed by Back or its own button leaves the field focused; drop the focus
    // with it so the history gives way to the results again.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (!imeVisible && focused) focus.clearFocus() }
    // Past searches, filtered by what's typed: while typing, and on the empty page.
    val history = SearchHistory.matching(state.query)
    val showHistory = history.isNotEmpty() && (focused || state.submitted.isEmpty())
    // The search bar rides with the header: both slide away together while scrolling results.
    CollapsingHeaderLayout(
        resetKey = Triple(scrollToTop, state.generation, showHistory),
        header = { Column {
        ScreenHeader(title = stringResource(R.string.ui_search_title))
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(60.dp)
                .clip(CircleShape)
                .background(c.surfaceContainerHigh)
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (state.query.isEmpty()) {
                    Text(stringResource(R.string.ui_search_hint), color = c.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = OwnDirectionText.merge(TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)),
                    cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
                    // Hardware Enter doesn't go through the IME action; treat it the same.
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { e ->
                        if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                            if (e.type == KeyEventType.KeyDown) submit()
                            true
                        } else false
                    },
                )
            }
            if (state.query.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(44.dp).pressable { state.query = "" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, stringResource(R.string.ui_clear_search), tint = c.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(8.dp))
        } },
    ) { top ->
        Box(Modifier.fillMaxSize()) {
        val listShown = showHistory || (state.results.isNotEmpty() && state.error == null)
        Box(Modifier.padding(top = if (listShown) 0.dp else top)) {
        when {
            showHistory -> SearchHistoryList(
                history = history,
                contentPadding = contentPadding.withTop(top),
                onSearch = { state.query = it; submit() },
            )
            state.loading && state.results.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), Alignment.Center) {
                CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
            }
            state.error != null -> EmptyState(
                icon = Icons.Rounded.CloudOff,
                title = stringResource(R.string.ui_search_error_title),
                body = state.error.orEmpty(),
                action = { PillButton(stringResource(R.string.ui_try_again), { state.submit() }) },
            )
            state.submitted.isEmpty() -> EmptyState(
                icon = Icons.Rounded.TravelExplore,
                title = stringResource(R.string.ui_search_intro_title),
                body = stringResource(R.string.ui_search_intro_body),
            )
            state.results.isEmpty() -> EmptyState(
                icon = Icons.Rounded.History,
                title = stringResource(R.string.ui_search_nothing_found),
                body = stringResource(R.string.ui_search_no_match, state.submitted),
            )
            else -> {
                val list = rememberScrollToTop(scrollToTop)
                LaunchedEffect(state.generation) { list.scrollToItem(0) }
                LazyColumn(state = list, contentPadding = contentPadding.withTop(top)) {
                videoCards(state.results, columns, onOpen, firstFocus = firstResult)
            }
            }
        }
        }
        }
    }
}

@Composable
private fun SearchHistoryList(history: List<String>, contentPadding: PaddingValues, onSearch: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    LazyColumn(contentPadding = contentPadding) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
            ) {
                SectionTitle(stringResource(R.string.ui_recent_searches), Modifier.weight(1f).alignByBaseline())
                Box(Modifier.alignByBaseline()) { TextLink(stringResource(R.string.ui_clear_all)) { SearchHistory.clear() } }
            }
        }
        items(history, key = { it }) { query ->
            Row(
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .pressable { onSearch(query) }
                    .height(52.dp)
                    .padding(start = 24.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.History, null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    query,
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier.size(44.dp).pressable { SearchHistory.remove(query) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, stringResource(R.string.ui_remove_search), tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            }
        }
    }
}
