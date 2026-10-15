package com.decio.skipii

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ShortsScreen(
    feed: FeedState,
    player: PlayerState,
    contentPadding: PaddingValues,
    scrollToTop: Int = 0,
) {
    val currentIndex = remember { mutableStateOf(0) }
    var showComments by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Ensure we have data; first load happens on first composition of the tab.
    LaunchedEffect(Unit) {
        if (!feed.loading && feed.videos.isEmpty() && feed.error == null) {
            feed.refresh()
        }
    }

    // When index changes, play that short.
    LaunchedEffect(currentIndex.value, feed.videos.size) {
        val idx = currentIndex.value
        if (idx < feed.videos.size) {
            val short = feed.videos[idx]
            if (player.current?.id != short.id) {
                player.playShort(short)
            }
        }
    }

    // Load more when nearing the end.
    LaunchedEffect(currentIndex.value, feed.videos.size) {
        if (currentIndex.value >= feed.videos.size - 3 && feed.hasMore && !feed.loading) {
            feed.loadMore()
        }
    }

    val shorts = feed.videos
    if (shorts.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
            if (feed.loading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp, modifier = Modifier.size(36.dp))
            } else if (feed.error != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(feed.error.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { feed.refresh() }) { Text("Retry") }
                }
            } else {
                // Feed loaded but returned nothing — a designed empty state, never a black void.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.SlowMotionVideo, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No Shorts to show right now", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { feed.refresh() }) { Text("Retry") }
                }
            }
        }
        return
    }

    val maxIndex = shorts.size - 1
    val index = currentIndex.value.coerceIn(0, maxIndex)
    val short = shorts[index]

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val reduced = rememberReducedMotion()
        val pageH = with(LocalDensity.current) { maxHeight.toPx() }
        val pageHState = rememberUpdatedState(pageH)
        var dy by remember { mutableFloatStateOf(0f) }
        var settleJob by remember { mutableStateOf<Job?>(null) }

        val dragState = rememberDraggableState { delta ->
            dy = (dy + delta).coerceIn(-pageHState.value, pageHState.value)
        }

        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { settleJob?.cancel() },
                    onDragStopped = { velocity ->
                        val i = currentIndex.value
                        val off = dy
                        val threshold = pageH * 0.22f
                        // Swipe up (negative) -> next; swipe down -> previous.
                        val goNext = (off <= -threshold || velocity < -1100f) && i < maxIndex
                        val goPrev = (off >= threshold || velocity > 1100f) && i > 0
                        if (reduced) {
                            dy = 0f
                            if (goNext) currentIndex.value = i + 1 else if (goPrev) currentIndex.value = i - 1
                        } else {
                            val target = when { goNext -> -pageH; goPrev -> pageH; else -> 0f }
                            settleJob?.cancel()
                            settleJob = launch {
                                animate(
                                    initialValue = dy,
                                    targetValue = target,
                                    animationSpec = if (target == 0f) spring(dampingRatio = 0.82f, stiffness = 520f)
                                    else tween(210, easing = Motion.Emphasized),
                                ) { v, _ -> dy = v }
                                if (goNext) { currentIndex.value = i + 1; dy = 0f }
                                else if (goPrev) { currentIndex.value = i - 1; dy = 0f }
                            }
                        }
                    },
                )
                .pointerInput(Unit) {
                    detectTapGestures {
                        player.controller?.let { c -> if (c.isPlaying) c.pause() else c.play() }
                    }
                },
        ) {
            // Neighbours are thumbnail-only: the app has ONE video surface and only the
            // current page may host it (a second host would fight for the shared texture).
            if (dy < -1f && index < maxIndex) {
                PagePreview(shorts[index + 1], player.videoAspect, dy + pageH)
            }
            if (dy > 1f && index > 0) {
                PagePreview(shorts[index - 1], player.videoAspect, dy - pageH)
            }

            Box(Modifier.fillMaxSize().graphicsLayer { translationY = dy }) {
                ShortPage(short = short, player = player)
                ShortsOverlay(
                    short = short,
                    player = player,
                    bottomPadding = contentPadding.calculateBottomPadding(),
                    onLike = { player.setLike(LikeStatus.Like) },
                    onComments = { showComments = true },
                    onShare = { shareShort(context, short) },
                    onMore = { showOptions = true },
                )
            }
        }

        if (showComments) {
            // Under the video's lower third, opening to roughly half the screen.
            CommentsPanel(player.comments, partialTop = maxHeight * 0.42f, onClose = { showComments = false })
        }
        if (showOptions) {
            VideoOptionsSheet(short, player, onDismiss = { showOptions = false })
        }
    }
}

@Composable
private fun PagePreview(short: VideoItem, videoAspect: Float, translationY: Float) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.translationY = translationY }
            .background(Color.Black),
    ) {
        Box(Modifier.fillMaxSize().aspectFit(videoAspect).clipToBounds()) {
            Thumbnail(short.thumbnail, Modifier.fillMaxSize(), radius = 0)
        }
    }
}

@Composable
fun ShortPage(
    short: VideoItem,
    player: PlayerState,
) {
    Box(Modifier.fillMaxSize()) {
        // The stage takes the short's shape (portrait), like the player's stage.
        Box(
            Modifier
                .fillMaxSize()
                .aspectFit(player.videoAspect)
                .clipToBounds(),
        ) {
            // Thumbnail UNDER the (transparent until it has a frame) TextureView, exactly as
            // the normal player does — so the still never covers the live video.
            Thumbnail(
                url = short.thumbnail,
                modifier = Modifier.fillMaxSize(),
                radius = 0,
            )
            LocalVideoSurface.current(Modifier.fillMaxSize())
        }

        // Gradient vignettes
        VignetteTop()
        VignetteBottom()
    }
}

@Composable
fun VignetteTop() {
    val density = LocalDensity.current
    val brush = remember(MaterialTheme.colorScheme.surface) { Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), (100 * density.density) to Color.Transparent) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush)
        }
    }
}

@Composable
fun VignetteBottom() {
    val density = LocalDensity.current
    val brush = remember(MaterialTheme.colorScheme.surface) { Brush.verticalGradient(0f to Color.Transparent, (120 * density.density) to Color.Black.copy(alpha = 0.7f)) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush)
        }
    }
}

@Composable
fun ShortsOverlay(
    short: VideoItem,
    player: PlayerState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onLike: () -> Unit,
    onComments: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
) {
    // The feed items carry no metadata; the player's loaded details do. Prefer them once the
    // current short has loaded, so the title/channel/avatar are real.
    val info = player.details?.item?.takeIf { it.id == short.id } ?: short
    val like = player.accountState?.like == LikeStatus.Like
    val subscribed = player.accountState?.subscribed
    val likes = player.displayLikes
    val comments = player.comments.total.toLong()

    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding + 20.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            // Channel row + title
            Row(verticalAlignment = Alignment.CenterVertically) {
                info.avatar?.let {
                    Thumbnail(
                        url = it,
                        modifier = Modifier.size(36.dp).clip(CircleShape),
                        radius = 18,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.channel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(info.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (subscribed != null) {
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (subscribed) Color.White.copy(alpha = 0.2f) else Color.White,
                        modifier = Modifier.pressable(pressedScale = 0.95f) { player.toggleSubscribe() },
                    ) {
                        Text(
                            if (subscribed) "Subscribed" else "Subscribe",
                            color = if (subscribed) Color.White else Color.Black,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Action rail
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    ShortsAction(
                        icon = if (like) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        label = "Like",
                        count = likes,
                        active = like,
                        onClick = onLike,
                    )
                    ShortsAction(Icons.Rounded.ChatBubble, "Comments", comments, onClick = onComments)
                    ShortsAction(Icons.Rounded.Send, "Share", 0, onClick = onShare)
                    ShortsAction(Icons.Rounded.MoreVert, "", 0, onClick = onMore)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Thin progress line
            val dur = player.durationMs
            if (dur > 0) {
                val progress = (player.positionMs.toFloat() / dur).coerceIn(0f, 1f)
                Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.3f))) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { scaleX = progress; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
                            .height(2.dp)
                            .background(Color.White),
                    )
                }
            }
        }
    }
}

@Composable
fun ShortsAction(
    icon: ImageVector,
    label: String,
    count: Long,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.pressable(pressedScale = 0.9f, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = if (active) c.primary else Color.White, modifier = Modifier.size(30.dp))
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (count > 0) {
            Spacer(Modifier.height(1.dp))
            Text(formatCount(count), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Share a short the same way the long-press menu does. */
private fun shareShort(context: Context, video: VideoItem) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "https://youtu.be/${video.id}")
        .putExtra(Intent.EXTRA_SUBJECT, video.title)
    context.startActivity(Intent.createChooser(send, null))
}

/** Play a short in the existing player, loop it, don't expand the mini-player. */
fun PlayerState.playShort(item: VideoItem) {
    setShortsVideo(true)
    controller?.let { ctrl ->
        ctrl.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
    }
    open(item, expand = false)
}

/** Called when leaving the Shorts tab: restore normal repeat mode and audio-only behaviour. */
fun PlayerState.leaveShorts() {
    setShortsVideo(false)
    controller?.let { ctrl ->
        ctrl.repeatMode = androidx.media3.common.Player.REPEAT_MODE_OFF
    }
}
