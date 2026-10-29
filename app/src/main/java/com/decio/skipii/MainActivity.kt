package com.decio.skipii

import android.Manifest
import android.app.Activity
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import java.lang.ref.WeakReference
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One window at a time (one player, one video texture): a newer one closes the one before.
        // That's a bubble opened while the app is open, or the app opened from Home while it sits
        // in a bubble (the bubble then goes away). Its task goes too, so Recents keeps no stale card;
        // PlaybackService.onTaskRemoved sees this window still open and plays on.
        current?.get()?.takeIf { it !== this && !it.isFinishing }?.finishAndRemoveTask()
        current = WeakReference(this)
        enableEdgeToEdge()
        // The media notification (and its lock-screen controls) needs this on Android 13+.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { SkipiiTheme { SkipiiApp() } }
    }

    override fun onStart() {
        super.onStart()
        AppUpdate.checkIfDue()
    }

    override fun onResume() {
        super.onResume()
        AppUpdate.onWindowResumed(this)
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: WeakReference<MainActivity>? = null

        /** The task of the app's open window; null when there's none (closed, or on its way out). */
        fun openTaskId(): Int? = current?.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.taskId
    }
}

enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    Subscriptions("Subscriptions", Icons.Outlined.Subscriptions, Icons.Rounded.Subscriptions),
    Shorts("Shorts", Icons.Outlined.SlowMotionVideo, Icons.Rounded.SlowMotionVideo),
    Search("Search", Icons.Rounded.Search, Icons.Rounded.Search),
    Library("Library", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
}

// The mini-player floats 8 dp above the nav pill (NavBar.kt), capped at 368 dp, centered.
private val MiniWidth = 368.dp
private val MiniGap = 8.dp

@Composable
fun SkipiiApp() {
    val c = MaterialTheme.colorScheme
    val player = rememberPlayerState()
    val scope = rememberCoroutineScope()
    val search = remember { SearchState(scope) }
    val lib = remember { LibraryData(scope) }
    var signingIn by remember { mutableStateOf(false) }
    var accountSheet by remember { mutableStateOf(false) }
    var openPlaylist by remember { mutableStateOf<PlaylistRef?>(null) }
    var openChannel by remember { mutableStateOf<String?>(null) }
    // Kept after closing so the page can slide out with its content.
    var channelState by remember { mutableStateOf<ChannelState?>(null) }
    val playlistFeed = remember(openPlaylist?.id) { openPlaylist?.let { lib.playlistFeed(it.id) } }
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    // Shorts: its own feed and players; ‹ (and Back) return to the tab it was opened from.
    // One per process (SkipiiApplication), so its bank keeps filling with no window open.
    val shorts = (LocalContext.current.applicationContext as SkipiiApplication).shorts
    DisposableEffect(shorts) { onDispose { shorts.leave() } }
    var shortsFrom by rememberSaveable { mutableStateOf(Tab.Home) }
    // Bumped when the already-selected tab is tapped; that tab's list scrolls to the top.
    val scrollToTop = remember { mutableStateMapOf<Tab, Int>() }
    val reduced = rememberReducedMotion()
    val morph = remember { PlayerMorph(scope) { expanded -> player.expanded = expanded } }

    // The screen stays on while a video plays (full player, mini-player or fullscreen);
    // paused, the normal timeout applies again. Only affects the visible window.
    val rootView = LocalView.current
    DisposableEffect(player.isPlaying, shorts.playing) {
        rootView.keepScreenOn = player.isPlaying || shorts.playing
        onDispose { rootView.keepScreenOn = false }
    }

    // Opening the player drops any text focus (the search box behind it), so the keyboard
    // can't come back over the video.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(player.expanded) {
        if (player.expanded) { focusManager.clearFocus(force = true); keyboard?.hide() }
    }

    // Off screen (app in background, screen locked): audio only.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { player.setBackground(true); shorts.setForeground(false) }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { player.setBackground(false); shorts.setForeground(true) }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // As in Castii: at least 8 dp off the bottom, and extra clearance above a three-button bar.
    val stackBottom = navBottom.coerceAtLeast(8.dp) +
        if (WindowInsets.tappableElement.asPaddingValues().calculateBottomPadding() > 0.dp) 10.dp else 0.dp
    var optionsFor by remember { mutableStateOf<VideoItem?>(null) }
    // On Shorts the app's video waits, paused, out of sight (no mini-player).
    val hasMini = player.current != null && tab != Tab.Shorts
    // Lists scroll fully clear of the floating nav (and mini-player when present).
    val contentPadding = PaddingValues(
        bottom = stackBottom + NavHeight + (if (hasMini) MiniHeight + MiniGap else 0.dp) + 16.dp,
    )

    // Fullscreen is whenever the open player is shown in landscape (rotation or button).
    val activity = LocalContext.current as Activity
    val orientation = rememberOrientationController(activity as ComponentActivity)
    val playerOpen = player.current != null && player.expanded
    // In split screen this is the pane's shape, not the phone's.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Audio only has no picture to go fullscreen with: the open player stays portrait.
    orientation.Bind(playerOpen && !AudioOnly.enabled, landscape)
    val pip = remember(activity) { PipController(activity) }
    // Closing the picture-in-picture window pauses (expanding it just comes back).
    pip.Bind(onClosed = { player.controller?.pause() })
    // The PiP window can be any shape: hold whatever was showing when it opened.
    val fullscreenBeforePip = remember { mutableStateOf(false) }
    val showFullscreen = if (pip.active) fullscreenBeforePip.value else orientation.showsFullscreen(playerOpen, landscape)
    SideEffect { if (!pip.active) fullscreenBeforePip.value = showFullscreen }
    LaunchedEffect(showFullscreen) { player.fullscreen = showFullscreen }
    SystemBarsHidden(showFullscreen)
    // A vertical video's fullscreen stays upright (Fullscreen.kt).
    fun toggleFullscreen() = if (showFullscreen) orientation.exitFullscreen() else orientation.enterFullscreen(vertical = player.videoAspect < 1f)

    BackHandler(enabled = player.expanded) { player.expanded = false }
    BackHandler(enabled = !player.expanded && tab != Tab.Home && openPlaylist == null) { tab = if (tab == Tab.Shorts) shortsFrom else Tab.Home }
    BackHandler(enabled = !player.expanded && openPlaylist != null) { openPlaylist = null }
    BackHandler(enabled = signingIn) { signingIn = false }
    BackHandler(enabled = !player.expanded && openChannel != null) { openChannel = null }
    BackHandler(enabled = tab == Tab.Shorts && shorts.commentsOpen) { shorts.commentsOpen = false }
    // Registered last so it wins: in fullscreen, Back returns to the portrait player first.
    BackHandler(enabled = showFullscreen) { orientation.exitFullscreen() }

    // The open player's bar (PlayerBar.kt): its tools card / quality list close on Back, on a
    // tap anywhere else, and whenever the player stops being the open portrait page.
    val playerBar = remember { PlayerBarState(scope) }
    BackHandler(enabled = playerBar.isOpen && playerOpen && !showFullscreen) { playerBar.closeAll() }
    LaunchedEffect(playerOpen, showFullscreen, player.sheet) {
        if (!playerOpen || showFullscreen || player.sheet != null) playerBar.closeAll()
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val density = LocalDensity.current

    // Shorts (Shorts.kt) play on their own players: the app's video pauses on the way in and
    // carries on, from the same spot in the mini-player, on the way out (Derek, 2026-10-28).
    // One progress turns the tab bar into the glass bar: 620 ms in, 460 ms out, both moving at once.
    val shortsMorph = remember { androidx.compose.animation.core.Animatable(if (tab == Tab.Shorts) 1f else 0f) }
    var resumeMain by remember { mutableStateOf(false) }
    LaunchedEffect(tab) {
        if (tab == Tab.Shorts) {
            resumeMain = resumeMain || player.isPlaying
            player.controller?.pause()
            player.setVideoAside(true)
            shorts.enter()
        } else if (shorts.active) {
            shorts.leave()
            player.setVideoAside(false)
            if (resumeMain) player.controller?.play()
            resumeMain = false
        }
    }
    // Shorts turned off (account sheet) while it was the tab: back to where it was opened from.
    LaunchedEffect(ShortsSetting.enabled) { if (!ShortsSetting.enabled && tab == Tab.Shorts) tab = shortsFrom }
    LaunchedEffect(tab == Tab.Shorts) {
        if (tab == Tab.Shorts) shortsMorph.animateTo(1f, if (reduced) snap() else tween(ShortsEnterMs, easing = androidx.compose.animation.core.LinearEasing))
        else shortsMorph.animateTo(0f, if (reduced) snap() else tween(ShortsExitMs, easing = ShortsExitEasing))
    }
    val shortsShown by remember { derivedStateOf { shortsMorph.value > 0f } }
    SideEffect {
        StatusBarOverVideo.shorts = tab == Tab.Shorts && openChannel == null
        shorts.onMessage = { player.actionMessage = it }
        shorts.mainBusy = { player.isBuffering }
    }

    // Taps, back and opening a video set `expanded`; this turns that into the morph.
    // Gesture releases settle themselves and are left alone (see PlayerMorph.animateTo).
    LaunchedEffect(player.expanded, player.current?.id) {
        if (player.current == null) morph.progress.snapTo(0f) else morph.animateTo(player.expanded, reduced)
    }
    val showPlayer by remember {
        // Held until the morph has completely finished, so the video changes hands once.
        derivedStateOf { player.current != null && (player.expanded || morph.dragging || morph.progress.isRunning || morph.progress.value > 0f) }
    }

    fun showChannel(url: String) {
        // From the player: minimize it so the channel page is visible.
        player.expanded = false
        if (channelState?.url != url) channelState = ChannelState(url, scope)
        openChannel = url
    }
    // A channel opened from Shorts sits over the short, which waits paused.
    SideEffect { shorts.cover(tab == Tab.Shorts && openChannel != null) }
    BackHandler(enabled = tab == Tab.Shorts && shorts.channelMenu) { shorts.channelMenu = false }

    BoxWithConstraints(Modifier.fillMaxSize().background(c.surface)) {
        val stageFloor = maxOf(StageMinAspect, constraints.maxWidth / (constraints.maxHeight * StageMaxHeightShare))
        SideEffect { player.stageFloor = stageFloor }
        // Both ends of the morph, from the same constants the mini-player and player use.
        val stageAspect = player.stageAspect
        val geometry = remember(constraints.maxWidth, constraints.maxHeight, stackBottom, statusTop, density, stageAspect) {
            with(density) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val miniBottom = h - (stackBottom + NavHeight + MiniGap).toPx()
                val miniW = minOf(w - 32.dp.toPx(), MiniWidth.toPx())
                val miniLeft = (w - miniW) / 2f
                val miniCard = Rect(miniLeft, miniBottom - MiniHeight.toPx(), miniLeft + miniW, miniBottom)
                val thumb = Rect(
                    Offset(miniCard.left + MiniThumbStart.toPx(), miniCard.top + (MiniHeight - MiniThumbHeight).toPx() / 2f),
                    Size(MiniThumbWidth.toPx(), MiniThumbHeight.toPx()),
                )
                val side = StageSideGap.toPx()
                val stageTop = (statusTop + StageTopGap).toPx()
                val stage = Rect(side, stageTop, w - side, stageTop + (w - 2 * side) / stageAspect)
                MorphGeometry(
                    screen = Size(w, h),
                    miniCard = miniCard,
                    miniThumb = thumb,
                    stage = stage,
                    miniRadius = MiniRadius.toPx(),
                    thumbRadius = MiniThumbRadius.toPx(),
                    stageRadius = StageRadius.toPx(),
                )
            }
        }
        SideEffect { morph.geometry = geometry }

        // One video surface that moves between player, fullscreen and mini-player (never two
        // at once), so the picture is never rebuilt and never blinks back to the thumbnail.
        val sharedSurface = remember(player) {
            androidx.compose.runtime.movableContentOf { m: Modifier -> VideoSurface(player.controller, m) }
        }
        CompositionLocalProvider(
            // In picture-in-picture only the PiP view below shows the video.
            LocalVideoSurface provides if (pip.active) NoVideoSurface else sharedSurface,
            LocalPictureInPicture provides pip,
            LocalVideoMenu provides { optionsFor = it },
            LocalChannelOpener provides { url -> showChannel(url) },
        ) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                else (fadeIn(tween(Motion.PageEnterMs, easing = Motion.Emphasized)) +
                    slideInVertically(tween(Motion.PageRiseMs, easing = Motion.Emphasized)) { (it / 35).coerceIn(8, 24) }) togetherWith
                    fadeOut(tween(Motion.PageExitMs))
            },
            label = "tabs",
        ) { t ->
            when (t) {
                Tab.Home -> HomeScreen(
                    lib, contentPadding, onOpen = player::open, onSearch = { tab = Tab.Search },
                    onSignIn = { signingIn = true }, onAccount = { accountSheet = true },
                    scrollToTop = scrollToTop[Tab.Home] ?: 0,
                )
                Tab.Subscriptions -> SubscriptionsScreen(
                    lib, contentPadding, onOpen = player::open,
                    onSignIn = { signingIn = true }, onAccount = { accountSheet = true },
                    scrollToTop = scrollToTop[Tab.Subscriptions] ?: 0,
                )
                Tab.Shorts -> ShortsScreen(shorts, { shortsMorph.value }, overlayBottom = stackBottom + GlassHeight + 16.dp)
                Tab.Search -> SearchScreen(search, contentPadding, onOpen = player::open, scrollToTop = scrollToTop[Tab.Search] ?: 0)
                Tab.Library -> LibraryScreen(
                    lib, contentPadding, onOpen = player::open, onOpenPlaylist = { openPlaylist = it },
                    onSignIn = { signingIn = true }, onAccount = { accountSheet = true },
                    scrollToTop = scrollToTop[Tab.Library] ?: 0,
                )
            }
        }

        // A playlist opens as a page over the tabs (slides in from the right).
        AnimatedVisibility(
            visible = openPlaylist != null,
            enter = if (reduced) fadeIn(snap()) else slideInHorizontally(tween(340, easing = Motion.Emphasized)) { it } + fadeIn(tween(120)),
            exit = if (reduced) fadeOut(snap()) else slideOutHorizontally(tween(300, easing = Motion.Emphasized)) { it } + fadeOut(tween(220)),
        ) {
            val pl = openPlaylist
            val feed = playlistFeed
            if (pl != null && feed != null) {
                PlaylistScreen(pl, feed, contentPadding, onBack = { openPlaylist = null }, onOpen = player::open)
            }
        }

        // A channel opens the same way, over everything below the mini-player.
        AnimatedVisibility(
            visible = openChannel != null,
            enter = if (reduced) fadeIn(snap()) else slideInHorizontally(tween(340, easing = Motion.Emphasized)) { it } + fadeIn(tween(120)),
            exit = if (reduced) fadeOut(snap()) else slideOutHorizontally(tween(300, easing = Motion.Emphasized)) { it } + fadeOut(tween(220)),
        ) {
            channelState?.let { ch ->
                ChannelScreen(
                    ch, contentPadding, onBack = { openChannel = null },
                    // A video from a channel opened in Shorts plays in the app's player: Shorts closes.
                    onOpen = { v -> if (tab == Tab.Shorts) tab = shortsFrom; player.open(v) },
                    onMessage = { player.actionMessage = it },
                )
            }
        }

        // Ledgii's bottom vignette: the page color fades in over the last 130 dp (plus the
        // system bar), so content dissolves behind the mini-player and tab bar instead of
        // running hard into them. Drawn only — it never intercepts touches.
        val pageColor = c.surface
        val vignette = remember(pageColor) { vignetteBrush(pageColor, opaqueAtTop = false) }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(navBottom + 130.dp)
                // Not over a short: the video runs to the screen's edge.
                .graphicsLayer { alpha = 1f - shortsMorph.value }
                .drawBehind { drawRect(vignette) },
        )
        // Swiping home from here never scrolls the page (the tab bar above still wins its taps).
        BottomGestureGuard(Modifier.align(Alignment.BottomCenter))

        if (hasMini) {
            // Swipe up expands (the morph); swipe down follows the finger and, past a
            // threshold or with a flick, slides the mini-player away and stops playback.
            val dismiss = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(player.current?.id) { dismiss.snapTo(0f) }
            val dismissDistance = with(density) { 56.dp.toPx() }
            MiniPlayer(
                player,
                onExpand = { player.expanded = true },
                showVideo = !showPlayer && !showFullscreen,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = stackBottom + NavHeight + MiniGap)
                    .widthIn(max = MiniWidth)
                    // Fades as the video grows out of it; slides and fades as it's dismissed.
                    .graphicsLayer {
                        translationY = dismiss.value
                        // Hidden for the whole morph: the shrinking page draws the mini-player
                        // itself (it stays touchable underneath, so taps still work mid-collapse).
                        alpha = (if (showPlayer) 0f else 1f) * (1f - (dismiss.value / (dismissDistance * 1.6f)).coerceIn(0f, 1f))
                    }
                    .draggable(
                        state = rememberDraggableState { delta ->
                            // Once a gesture drives the morph it stays on the morph: switching to
                            // the dismiss slide mid-gesture (up a little, then back down) never
                            // settled the morph, which then stayed "dragging" at its mini end —
                            // a dead mini-player and live player-bar buttons under the tabs.
                            if (!morph.dragging && morph.progress.value <= 0f && (delta > 0f || dismiss.value > 0f)) {
                                scope.launch { dismiss.snapTo((dismiss.value + delta).coerceAtLeast(0f)) }
                            } else {
                                morph.dragBy(delta)
                            }
                        },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (morph.dragging) {
                                morph.settle(velocity, reduced)
                            } else if (dismiss.value > 0f) {
                                if (dismiss.value > dismissDistance || velocity > 800f) {
                                    scope.launch {
                                        if (!reduced) dismiss.animateTo(dismissDistance * 2.2f, tween(200, easing = Motion.Emphasized))
                                        player.close()
                                    }
                                } else {
                                    scope.launch { dismiss.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 600f)) }
                                }
                            }
                        },
                    ),
            )
        }

        // The player morphs out of (and back into) the mini-player; see PlayerMorph.
        if (showPlayer && !showFullscreen) {
            PlayerScreen(
                player, morph,
                onOpen = player::open,
                onCollapse = { player.expanded = false },
                onFullscreen = { toggleFullscreen() },
                bottomClearance = stackBottom + NavHeight + PlayerBarRaise + 16.dp,
            )
        }

        // Over the player: while it's open the nav pill is the player's own bar (NavBar.kt).
        // It steps down out of the way of the comments panel (its composer sits there).
        val barAside by animateFloatAsState(
            if (player.sheet == PlayerSheet.Comments && playerOpen) 1f else 0f,
            if (reduced) snap() else tween(280, easing = Motion.Emphasized), label = "barAside",
        )
        // A tap anywhere outside the open card / list closes it (and goes no further).
        if (playerBar.isOpen && playerOpen) {
            Box(
                Modifier.fillMaxSize().pointerInput(playerBar) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        playerBar.closeAll()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
            )
        }
        NavPill(
            selected = tab,
            onSelect = { selected ->
                when {
                    // Re-tapping the current tab: back out of a playlist, else scroll to top.
                    selected == tab && openChannel != null -> openChannel = null
                    selected == tab && openPlaylist != null -> openPlaylist = null
                    selected == tab -> scrollToTop[selected] = (scrollToTop[selected] ?: 0) + 1
                    else -> {
                        if (selected == Tab.Shorts) shortsFrom = tab
                        openPlaylist = null; openChannel = null; tab = selected
                    }
                }
            },
            player = player,
            morph = morph,
            controlsActive = player.current != null && (player.expanded || morph.dragging),
            bar = playerBar,
            onFullscreen = { toggleFullscreen() },
            shorts = { shortsMorph.value },
            tabsEnabled = !shortsShown,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = stackBottom)
                .graphicsLayer {
                    // Moved fully off screen, so it can't catch touches meant for the composer.
                    translationY = barAside * (NavHeight + PlayerBarRaise + stackBottom + 24.dp).toPx()
                    alpha = 1f - barAside
                },
        )

        // The tab bar's Shorts form: ‹ and the glass pill, out of the tab bar's own spot. It steps
        // down out of the way of the comments panel (its composer sits there), as the player's bar does.
        if (shortsShown) {
            val shortsAside by animateFloatAsState(
                if (shorts.commentsOpen || openChannel != null) 1f else 0f,
                if (reduced) snap() else tween(280, easing = Motion.Emphasized), label = "shortsAside",
            )
            ShortsBar(
                shorts,
                progress = { shortsMorph.value },
                onBack = { tab = shortsFrom },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = stackBottom)
                    .widthIn(max = NavWidth)
                    .fillMaxWidth()
                    .height(NavHeight)
                    .graphicsLayer {
                        translationY = shortsAside * (NavHeight + stackBottom + 24.dp).toPx()
                        alpha = 1f - shortsAside
                    },
            )
        }

        if (shortsShown) ShortsChannelMenu(shorts, bottom = stackBottom + GlassHeight + 10.dp, onChannel = ::showChannel)

        if (showFullscreen) FullscreenPlayer(player, onExit = { orientation.exitFullscreen() })

        if (accountSheet) {
            AccountSheet(
                onSignIn = { signingIn = true },
                onSignedOut = { lib.reset(); player.refreshAccountState() },
                onDismiss = { accountSheet = false },
            )
        }
        if (signingIn) {
            SignInScreen(
                onDone = { signingIn = false; lib.reset(); player.refreshAccountState() },
                onCancel = { signingIn = false },
            )
        }

        when (player.sheet) {
            PlayerSheet.Quality -> QualitySheet(player, onDismiss = { player.sheet = null })
            PlayerSheet.Comments -> Unit // a panel inside the player (PlayerScreen)
            PlayerSheet.Queue -> QueueSheet(player, onDismiss = { player.sheet = null })
            null -> Unit
        }
        optionsFor?.let { video -> VideoOptionsSheet(video, player, onDismiss = { optionsFor = null }) }
        // Confirmations ("Added to queue") when the full player isn't showing its own.
        if (!showPlayer) {
            QueueMessage(player, Modifier.align(Alignment.BottomCenter).padding(bottom = stackBottom + NavHeight + (if (hasMini) MiniHeight + MiniGap else 0.dp) + 12.dp))
        }
        // Picture-in-picture: the window is just the video, over everything (kept composed
        // underneath, so expanding returns to exactly where things were).
        if (pip.active) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                sharedSurface(Modifier.fillMaxSize().aspectFit(player.videoAspect))
            }
        }
        }
    }
}

@Composable
private fun QueueMessage(player: PlayerState, modifier: Modifier) {
    val message = player.actionMessage ?: return
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(2_000)
        player.actionMessage = null
    }
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(50)).background(c.inverseSurface).padding(horizontal = 18.dp, vertical = 11.dp)) {
        Text(message, color = c.inverseOnSurface, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
