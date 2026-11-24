package com.decio.skipii

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** What the fullscreen drawer lists. */
private enum class DrawerTab { Queue, More, Channel }

/**
 * Fullscreen's drawer (0.6.0; Derek approved section 5 of docs/design/skipii-2.html, gestures per Neon's spec): a swipe
 * up in the middle of the video brings it up without leaving landscape. Tabs: the queue (when there is one), More
 * videos, and the channel's own among them (when some are and some aren't). Dark glass as the overlay's cards; no
 * live blur (0.5.1). A tap plays the video (the queue's, from the queue); a long press queues it. Drag it down, tap
 * the picture or press Back to close it ([FullscreenPlayer] owns those).
 */
@Composable
fun FullscreenDrawer(state: PlayerState, onDrag: (Float) -> Unit, onDragEnd: (Float) -> Unit, onPicked: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val related = state.details?.related.orEmpty().filterNot(Dismissed::hides)
    val channel = state.current?.channel.orEmpty()
    val fromChannel = related.filter { channel.isNotBlank() && it.channel == channel }
    val tabs = buildList {
        if (state.queue.isNotEmpty()) add(DrawerTab.Queue)
        add(DrawerTab.More)
        if (fromChannel.isNotEmpty() && fromChannel.size < related.size) add(DrawerTab.Channel)
    }
    // The tab itself, not its place: queuing the first video adds Queue in front and mustn't switch tabs.
    var picked by rememberSaveable(state.current?.id) { mutableStateOf<DrawerTab?>(tabs.first()) }
    val current = picked?.takeIf { it in tabs } ?: tabs.first()
    val videos = when (current) {
        DrawerTab.Queue -> state.queue
        DrawerTab.More -> related
        DrawerTab.Channel -> fromChannel
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color(0xFF121216).copy(alpha = 0.84f))
            // Swallow taps so nothing under the glass reacts; a vertical drag moves the drawer.
            .draggable(rememberDraggableState { onDrag(it) }, Orientation.Vertical, onDragStopped = { onDragEnd(it) })
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), Alignment.Center) {
            Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f)))
        }
        DrawerTabs(
            labels = tabs.map {
                when (it) {
                    DrawerTab.Queue -> listOf(stringResource(R.string.video_drawer_queue), state.queue.size.toString()).joinDots()
                    DrawerTab.More -> stringResource(R.string.player_more_videos)
                    DrawerTab.Channel -> stringResource(R.string.player_more_from, channel)
                }
            },
            selected = tabs.indexOf(current),
            onPick = { picked = tabs[it] },
        )
        Spacer(Modifier.height(10.dp))
        if (videos.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), Alignment.Center) {
                Text(
                    stringResource(if (current == DrawerTab.More && !Downloads.online) R.string.video_drawer_offline else R.string.video_drawer_empty),
                    color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            val list = rememberLazyListState()
            LaunchedEffect(current) { list.scrollToItem(0) }
            LazyRow(state = list, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(videos, key = { i, v -> "$i-${v.id}" }) { i, v ->
                    DrawerCard(
                        v,
                        onClick = {
                            onPicked()
                            if (current == DrawerTab.Queue) state.playFromQueue(i) else state.open(v)
                        },
                        onLongClick = if (current == DrawerTab.Queue) null else ({
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            state.addToQueue(v)
                        }),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun DrawerCard(video: VideoItem, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Column(Modifier.width(160.dp).pressable(pressedScale = 0.96f, onLongClick = onLongClick, onClick = onClick)) {
        Box {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 12)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(5.dp), small = true)
            QueuedBadge(video.id, Modifier.align(Alignment.TopStart).padding(5.dp), small = true)
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Spacer(Modifier.height(6.dp))
        Text(video.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** White words on the glass; the chosen one with a primary bar springing under it (as Home's chips). */
@Composable
private fun DrawerTabs(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val reduced = rememberReducedMotion()
    val spots = remember(labels) { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val left = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = spots[selected]
    LaunchedEffect(target) {
        val (l, w) = target ?: return@LaunchedEffect
        if (!placed || reduced) { left.snapTo(l); width.snapTo(w); placed = true; return@LaunchedEffect }
        val spec = spring<Float>(dampingRatio = 0.62f, stiffness = 450f)
        launch { left.animateTo(l, spec) }
        width.animateTo(w, spec)
    }
    Row(
        // Scrolls when "From <a long channel name>" doesn't fit (rows of choices never squeeze; 0.5.65).
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).drawBehind {
            if (!placed) return@drawBehind
            val h = 3.dp.toPx()
            drawRoundRect(primary, topLeft = Offset(left.value, size.height - h), size = Size(width.value, h), cornerRadius = CornerRadius(h / 2))
        },
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .onPlaced { spots[i] = it.positionInParent().x + it.size.width * 0.2f to it.size.width * 0.6f }
                    .pressable { onPick(i) }
                    .height(34.dp)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Color.White.copy(alpha = if (on) 1f else 0.55f), fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}
