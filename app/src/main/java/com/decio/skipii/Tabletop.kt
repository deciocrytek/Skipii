package com.decio.skipii

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** The deck's own black, whatever the theme (Derek, 2026-11-02: "always OLED black"). */
private val DeckBlack = Color.Black
/** A button on the deck: a step up from black so it reads as a key, never an outline. */
private val DeckKey = Color(0xFF1C1C1E)
private val DeckInk = Color.White
private val DeckInkDim = Color.White.copy(alpha = 0.62f)
/** Storyboard frames along the scrub surface. */
private const val ScrubFrames = 10

/**
 * Tabletop (Rail 3, Derek 2026-11-02): an open foldable stood half open on a table, its fold running
 * across. The video fills the top half; the bottom half, always pure black, is the deck: the time and
 * chapter, a wide scrub surface of storyboard frames you drag across anywhere (seeks on release, a tap
 * jumps), and the transport with plain buttons — speed, quality, −10, play, +10, captions, audio only,
 * next. No player bar, no pill. [foldTop] / [foldBottom]: the fold's edges from the window's top.
 */
@Composable
fun TabletopPlayer(player: PlayerState, foldTop: Dp, foldBottom: Dp) {
    val item = player.current ?: return
    Box(Modifier.fillMaxSize().background(DeckBlack).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        // Top half: the picture, as big as it fits.
        Box(Modifier.fillMaxWidth().height(foldTop), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().aspectFit(player.videoAspect)) {
                if (AudioOnly.enabled || player.hidesStaleVideo) Thumbnail(item.thumbnail, Modifier.fillMaxSize(), radius = 0)
                if (!AudioOnly.enabled) {
                    val surface = LocalVideoSurface.current
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (player.hidesStaleVideo) 0f else 1f }) { surface(Modifier.fillMaxSize()) }
                }
                CaptionLayer(player, bottom = 10.dp)
            }
            if (player.isBuffering) CircularProgressIndicator(color = DeckInk, strokeWidth = 4.dp, modifier = Modifier.size(40.dp))
        }
        // Bottom half: the deck.
        Column(
            Modifier.fillMaxSize().padding(top = foldBottom).padding(horizontal = 28.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 920.dp).fillMaxWidth()) {
                var scrubMs by remember(item.id) { mutableStateOf<Long?>(null) }
                TimeRow(player, scrubMs)
                Spacer(Modifier.height(10.dp))
                ScrubSurface(player, onScrub = { scrubMs = it })
                Spacer(Modifier.height(16.dp))
                DeckButtons(player)
            }
        }
    }
}

@Composable
private fun TimeRow(player: PlayerState, scrubMs: Long?) {
    val position = scrubMs ?: player.positionMs
    val duration = player.durationMs
    val chapter = player.chapterAt(position)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatDuration(position) + " / " + formatDuration(duration),
            color = if (scrubMs != null) MaterialTheme.colorScheme.primary else DeckInk,
            fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
        )
        Text(
            chapter.orEmpty(), color = DeckInkDim, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
        )
        if (duration > 0) Text("−" + formatDuration((duration - position).coerceAtLeast(0L)), color = DeckInkDim, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The scrub surface: the video's storyboard as a strip of frames (a plain track without one). Drag
 * anywhere along it and the landing spot follows the finger; it seeks on release. The part still to
 * play is dimmed; chapters show as gaps.
 */
@Composable
private fun ScrubSurface(player: PlayerState, onScrub: (Long?) -> Unit) {
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val duration = player.durationMs.coerceAtLeast(1L)
    val boards = player.details?.storyboards.orEmpty()
    val chapters = player.details?.chapters.orEmpty()
    val sheets = rememberStoryboardSheets(player)
    var width by remember { mutableStateOf(1) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shown = scrub ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val lift by animateFloatAsState(if (scrub != null) 1f else 0f, label = "scrubLift")
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .onSizeChanged { width = it.width.coerceAtLeast(1) }
                .pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun at(x: Float) = (x / width).coerceIn(0f, 1f)
                        scrub = at(down.position.x)
                        onScrub((scrub!! * duration).toLong())
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (change.positionChange() != Offset.Zero) {
                                change.consume()
                                scrub = at(change.position.x)
                                onScrub((scrub!! * duration).toLong())
                            }
                        }
                        scrub?.let { player.seekTo((it * duration).toLong()) }
                        scrub = null
                        onScrub(null)
                    }
                }
                .clip(shape)
                .background(DeckKey)
                .drawWithContent {
                    drawContent()
                    // Still to play: dimmed. The playhead: a bright line with a knob on top.
                    val x = shown * size.width
                    drawRect(Color.Black.copy(alpha = 0.62f), topLeft = Offset(x, 0f), size = Size(size.width - x, size.height))
                    chapters.drop(1).forEach { ch ->
                        val cx = ch.startMs.toFloat() / duration * size.width
                        drawRect(DeckBlack, topLeft = Offset(cx - 1.5.dp.toPx(), 0f), size = Size(3.dp.toPx(), size.height))
                    }
                    val line = 4.dp.toPx() + lift * 1.5.dp.toPx()
                    drawRoundRect(c.primary, topLeft = Offset(x - line / 2, 0f), size = Size(line, size.height), cornerRadius = CornerRadius(line))
                    // Knobs at both ends, so the playhead reads against any frame.
                    val knob = 7.dp.toPx() + lift * 2.dp.toPx()
                    drawCircle(c.primary, knob, Offset(x, knob * 0.6f))
                    drawCircle(c.primary, knob, Offset(x, size.height - knob * 0.6f))
                },
        ) {
            if (boards.isNotEmpty()) Row(Modifier.fillMaxSize()) {
                repeat(ScrubFrames) { i ->
                    StoryboardThumb(sheets, boards, ((i + 0.5f) / ScrubFrames * duration).toLong(), duration, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                // No storyboard: the played part as a fill.
                Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(c.primary.copy(alpha = 0.55f)))
            }
        }
        // While scrubbing: the landing time over the finger, kept inside the strip's width.
        scrub?.let { f ->
            val ms = (f * duration).toLong()
            val label = listOfNotNull("→ " + formatDuration(ms), player.chapterAt(ms)).joinDots()
            val half = with(density) { 70.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((f * width - half).coerceIn(0f, (width - 2 * half).coerceAtLeast(0f)).roundToInt(), with(density) { (-30).dp.roundToPx() }) }
                    .widthIn(min = 140.dp)
                    .clip(CircleShape)
                    .background(c.primary)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = c.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DeckButtons(player: PlayerState) {
    val c = MaterialTheme.colorScheme
    val hasNext = player.queue.isNotEmpty() || !player.details?.related.isNullOrEmpty()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DeckKeyButton(label = stringResource(R.string.bar_tool_speed), on = player.speed != 1f, onClick = { player.sheet = PlayerSheet.Speed }) {
            Text(PlaybackSpeed.label(player.speed) + "×", color = it, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        }
        DeckKeyButton(label = stringResource(R.string.bar_tool_quality), onClick = { player.sheet = PlayerSheet.Quality }) {
            Text(QualityPreference.height?.let { h -> "${h}p" } ?: stringResource(R.string.bar_quality_auto), color = it, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
        DeckIconButton(seekIcon(forward = false), seekLabel(-1)) { player.seekBy(-SeekStep.ms) }
        // Play: the one big key, full primary.
        Box(
            Modifier.size(76.dp).pressable { player.togglePlay() }.clip(CircleShape).background(c.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (player.isPlaying) R.string.bar_pause else R.string.bar_play),
                tint = c.onPrimary, modifier = Modifier.size(38.dp),
            )
        }
        DeckIconButton(seekIcon(forward = true), seekLabel(1)) { player.seekBy(SeekStep.ms) }
        DeckKeyButton(label = stringResource(R.string.bar_tool_captions), on = player.captions.on, onClick = {
            player.captions.turn(true)
            player.sheet = PlayerSheet.Captions
        }) { Icon(Icons.Rounded.ClosedCaption, null, tint = it, modifier = Modifier.size(24.dp)) }
        DeckKeyButton(label = stringResource(R.string.bar_audio_only), on = AudioOnly.enabled, onClick = { player.setAudioOnly(!AudioOnly.enabled) }) {
            Icon(Icons.Rounded.Headphones, null, tint = it, modifier = Modifier.size(24.dp))
        }
        DeckKeyButton(label = stringResource(R.string.bar_tool_next), enabled = hasNext, onClick = { player.playNextUp() }) {
            Icon(Icons.Rounded.SkipNext, null, tint = it, modifier = Modifier.size(26.dp))
        }
    }
}

/** A plain key: a dark tile with its symbol over a short label; full primary while its mode is on. */
@Composable
private fun RowScope.DeckKeyButton(label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit, symbol: @Composable (Color) -> Unit) {
    val c = MaterialTheme.colorScheme
    val ink = if (on) c.onPrimary else DeckInk
    Column(
        Modifier
            .weight(1f)
            .height(64.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) c.primary else DeckKey),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { symbol(ink) }
        Text(label, color = ink.copy(alpha = if (on) 1f else 0.8f), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeckIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(58.dp).pressable(onClick = onClick).clip(CircleShape).background(DeckKey), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = DeckInk, modifier = Modifier.size(28.dp))
    }
}
