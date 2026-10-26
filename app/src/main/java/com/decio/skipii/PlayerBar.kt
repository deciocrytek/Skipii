package com.decio.skipii

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size as CoilSize
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

// The open player's bar holds the playback controls (Derek, 2026-10-25: mockup A "Key" in
// docs/design/player-bar-bloom.html). At rest: audio only · −10 · play · +10 · ⋮, play dead centre.
// The ⋮ key, a hold or a swipe up opens a card of tools in a row (speed, quality, picture-in-picture,
// fullscreen, and next when the queue has one); speed and quality turn the bar into their panel; a
// sideways drag on play turns it into a chapter track. Swiping down on the card or a panel closes it.
// Everything follows three 0…1 values that fingers move directly and releases settle with a spring.
internal val SideSlotWidth = 41.dp
// 0.5.39 (Derek picked A "Card" in docs/design/player-bar-row.html): the dome's arc became one straight
// row of tools over the transport row, so the open bar is a short rounded card. It reaches CardHeight
// above the resting bar's bottom and CardBelow under it, where the chapter and time sit under the line.
private val CardWidth = 380.dp
private val CardHeight = 160.dp
private val CardBelow = 24.dp
/** With chapters, the strip holds the Chapters button instead, so it's a little taller. */
private val CardBelowChapters = 46.dp
private val ChaptersButtonHeight = 34.dp
// Never narrower than the bar at rest (CompactBarWidth): it only ever grows into these.
private val ScrubBarWidth = 380.dp
private val ScrubBarHeight = 112.dp
private val TrackWidth = 348.dp
private val TrackHeight = 40.dp
private val TrackBottom = 16.dp
private val OpenCorner = 32.dp
private val QualityWidth = 380.dp
private val ListRowHeight = 42.dp
private val ListRowGap = 4.dp
private val ListHeader = 50.dp
private const val ListMaxRows = 7
private val SpeedWidth = 380.dp
/** The speed ruler panel (SpeedPanel.kt) as it sits in the bar, above the transport row. */
private val SpeedPanelHeight = 214.dp
private val PetalSize = 58.dp
/** The tools' row: spread evenly across this width, centred this far above the resting bar's bottom. */
private val ToolRowWidth = 348.dp
private val ToolRowCenter = 113.dp
/** Swiping up while scrubbing: Cancel (over the preview frame) fades in once the finger is CancelFrom above
 *  the track, arms only when the finger reaches it, and disarms once it's back CancelSlack below it. */
private val CancelFrom = 24.dp
private val CancelSlack = 14.dp
/** A release faster than this (bar-heights per second) opens or closes regardless of where it is. */
private const val FlingOpen = 1.6f
/** A slow release closes what was open once dragged this far down, and opens what was closed once
 *  dragged this far up: dismissing takes less than opening, as with a sheet. */
private const val DismissFrom = 0.75f
private const val OpenFrom = 0.35f
/** After picking a quality, the list stays this long so the check is seen landing. */
private const val QualityPickedMs = 420L

/** Springs for the bar's values; a tiny visibility threshold so nothing snaps at the end (0.5.7). */
private val BarSpring = spring(dampingRatio = 0.78f, stiffness = 420f, visibilityThreshold = 0.0005f)

internal enum class BarTool { Speed, Quality, Pip, Fullscreen, Next }

/** What the bar can turn into besides the card. */
internal enum class BarPanel { Quality, Speed, Chapters }

/** The card's tools, left to right: Next only when the queue really has something next. */
internal fun barTools(player: PlayerState, pip: PipController?): List<BarTool> = buildList {
    add(BarTool.Speed)
    add(BarTool.Quality)
    if (pip?.supported == true) add(BarTool.Pip)
    add(BarTool.Fullscreen)
    if (player.queue.isNotEmpty()) add(BarTool.Next)
}

@Stable
internal class PlayerBarState(private val scope: CoroutineScope) {
    /** The tools card: 0 closed … 1 open. */
    val card = Animatable(0f, visibilityThreshold = 0.0005f)
    /** The bar as a panel ([panelKind]): 0 closed … 1 open. */
    val panel = Animatable(0f, visibilityThreshold = 0.0005f)
    var panelKind by mutableStateOf(BarPanel.Quality)
    /** Scrubbing on the play pill: 0 the bar … 1 the chapter track. */
    val scrub = Animatable(0f, visibilityThreshold = 0.0005f)
    /** Where an ongoing scrub would land. */
    var previewMs by mutableStateOf<Long?>(null)
    /** Swiping up off a scrub: 0 … 1 the Cancel button fully out (follows the finger). */
    val cancel = Animatable(0f, visibilityThreshold = 0.0005f)
    /** Letting go now would drop the scrub (the finger is up on Cancel). */
    var cancelArmed by mutableStateOf(false)
    /** The tool under a held finger (hold → slide → release), or null. */
    var hot by mutableStateOf<BarTool?>(null)
    /** The tools on the card right now (set by the bar as they change). */
    var tools by mutableStateOf(listOf(BarTool.Speed, BarTool.Quality, BarTool.Fullscreen))
    /** The video has chapters (set by the bar): the card's bottom strip becomes the Chapters button. */
    var hasChapters by mutableStateOf(false)
    /** The open chapter list, so a pull down from its top closes the panel rather than scrolling. */
    var chapterList: LazyListState? = null
    var reduced = false

    /** Open or opening: Back and a tap outside close it. */
    val isOpen by derivedStateOf { card.targetValue > 0f || panel.targetValue > 0f }
    val cardShown by derivedStateOf { card.value > 0.001f }
    val panelShown by derivedStateOf { panel.value > 0.001f }
    val scrubShown by derivedStateOf { scrub.value > 0.001f }
    /** Taps on the tools only once the card is (being) opened, not while it folds away. */
    val cardLive by derivedStateOf { card.targetValue >= 1f }
    val panelLive by derivedStateOf { panel.targetValue >= 1f }

    private fun spec(): AnimationSpec<Float> = if (reduced) snap() else BarSpring

    fun animate(value: Animatable<Float, *>, target: Float, velocity: Float = 0f) {
        scope.launch { value.animateTo(target, spec(), initialVelocity = if (reduced) 0f else velocity) }
    }
    fun snap(value: Animatable<Float, *>, to: Float) { scope.launch { value.snapTo(to) } }

    /** The scrub is over: seek to where it landed unless it was [cancelled]; the track folds back. */
    fun endScrub(player: PlayerState, cancelled: Boolean) {
        if (!cancelled) previewMs?.let { player.seekTo(it) }
        previewMs = null
        cancelArmed = false
        animate(scrub, 0f)
        animate(cancel, 0f)
    }

    fun openCard() { animate(panel, 0f); animate(card, 1f) }
    fun openPanel(kind: BarPanel) { panelKind = kind; animate(card, 0f); animate(panel, 1f) }
    fun closeAll() { hot = null; animate(card, 0f); animate(panel, 0f) }
    /** A finger let go of [value] moving at [velocity] (units per second): open or close it. */
    fun settle(value: Animatable<Float, *>, velocity: Float, wasOpen: Boolean) {
        val open = when {
            velocity > FlingOpen -> true
            velocity < -FlingOpen -> false
            else -> value.value > if (wasOpen) DismissFrom else OpenFrom
        }
        animate(value, if (open) 1f else 0f, velocity)
    }
}

/** The values the bar's size and contents follow this frame, gated to the fully open player. */
private class BarAmounts(val t: Float, val p: Float, val s: Float)

private fun PlayerBarState.amounts(gate: Float) = BarAmounts(card.value * gate, panel.value * gate, scrub.value * gate)

/** The card's strip under the resting bar: chapter · time, or the Chapters button. */
private fun PlayerBarState.cardBelow(): Dp = if (hasChapters) CardBelowChapters else CardBelow

/** Quality rows shown: Auto + every height. */
private fun qualityRows(player: PlayerState) = 1 + (player.details?.qualities?.size ?: 0)

private fun Density.panelSize(kind: BarPanel, player: PlayerState): Size = when (kind) {
    BarPanel.Quality -> Size(
        QualityWidth.toPx(),
        (ListHeader + PlayerBarHeight + 6.dp).toPx() + qualityRows(player).coerceAtMost(ListMaxRows) * (ListRowHeight + ListRowGap).toPx(),
    )
    BarPanel.Speed -> Size(SpeedWidth.toPx(), (SpeedPanelHeight + PlayerBarHeight + 6.dp).toPx())
    BarPanel.Chapters -> Size(ChaptersWidth.toPx(), (ListHeader + PlayerBarHeight + 6.dp + chapterRowsHeight(player)).toPx())
}

private val ChaptersWidth = 380.dp
private val ChapterRowHeight = 58.dp
private const val ChapterMaxRows = 6
private fun chapterRowsHeight(player: PlayerState): Dp =
    (ChapterRowHeight + ListRowGap) * (player.details?.chapters?.size ?: 0).coerceIn(1, ChapterMaxRows)

/** Where the chapter rows sit in the open panel (the bar's own pixels, top to bottom). */
private fun Density.chaptersListSpan(barH: Float, player: PlayerState): ClosedFloatingPointRange<Float> {
    val bottom = barH - (PlayerBarHeight + 6.dp).toPx()
    return (bottom - chapterRowsHeight(player).toPx())..bottom
}

/**
 * The bar's size: rest ([restW] × [restH], which the tab morph is still growing into while
 * [gate] < 1) blended toward the card, the open panel and the scrub track by how open each is.
 */
internal fun Density.playerBarSize(bar: PlayerBarState, player: PlayerState, restW: Float, restH: Float, maxW: Float, gate: Float): Size {
    val a = bar.amounts(gate)
    val cardW = CardWidth.toPx().coerceAtMost(maxW)
    val pan = panelSize(bar.panelKind, player)
    val w = restW + (cardW - restW) * a.t + (pan.width.coerceAtMost(maxW) - restW) * a.p + (ScrubBarWidth.toPx().coerceAtMost(maxW) - restW) * a.s
    val h = restH + (CardHeight.toPx() + bar.cardBelow().toPx() - restH) * a.t + (pan.height - restH) * a.p + (ScrubBarHeight.toPx() - restH) * a.s
    return Size(w.coerceAtLeast(1f), h.coerceAtLeast(1f))
}

/** How far the bar reaches below the resting bar's bottom (the card's time row); everything else stays put. */
internal fun Density.playerBarBelow(bar: PlayerBarState, gate: Float): Float = bar.cardBelow().toPx() * bar.amounts(gate).t.coerceAtLeast(0f)

/**
 * The bar's outline at [size]: a capsule at rest; the card, panels and the track get softer
 * square corners.
 */
internal fun Density.playerBarShape(bar: PlayerBarState, size: Size, gate: Float): Shape {
    val a = bar.amounts(gate)
    val r0 = if (a.t + a.p + a.s <= 0f) size.height / 2f else PlayerBarHeight.toPx() / 2f
    val open = OpenCorner.toPx()
    val k = a.t + a.p + a.s
    var topX = r0 + (open - r0) * k
    var topY = topX
    var bottom = topX
    topX = topX.coerceIn(0f, size.width / 2f)
    bottom = bottom.coerceIn(0f, size.width / 2f)
    if (topY + bottom > size.height) { val k = size.height / (topY + bottom); topY *= k; bottom *= k }
    return BarOutline(topX, topY.coerceAtLeast(0f), bottom.coerceAtLeast(0f))
}

private class BarOutline(private val topX: Float, private val topY: Float, private val bottom: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val top = CornerRadius(topX, topY)
        val low = CornerRadius(bottom, bottom)
        return Outline.Rounded(RoundRect(Rect(Offset.Zero, size), top, top, low, low))
    }
}

/** Where the transport row puts play, in the bar's own pixels (the row sits above the progress hairline, play in its middle). */
private fun Density.playRect(barW: Float, barH: Float): Rect {
    val w = PlaySlotWidth.toPx()
    val bottom = barH - PlayerProgressRoom.toPx() - PlayerBarPadding.toPx()
    val top = barH - PlayerProgressRoom.toPx() - PlayerRowHeight.toPx() + PlayerBarPadding.toPx()
    return Rect(barW / 2f - w / 2f, top, barW / 2f + w / 2f, bottom)
}

/** The progress hairline at rest, under the transport row (0.5.38: 10% smaller, 248×2.7). */
private val HairlineInset = 34.dp
private val HairlineHeight = 2.7.dp
private val HairlineBottom = 5.dp
private fun Density.hairlineRect(barW: Float, barH: Float): Rect {
    val w = (CompactBarWidth - HairlineInset * 2).toPx()
    val bottom = barH - HairlineBottom.toPx()
    return Rect(barW / 2f - w / 2f, bottom - HairlineHeight.toPx(), barW / 2f + w / 2f, bottom)
}

/** The progress line at scrub amount [s]: the hairline (0) swelling into the chapter track (1). */
private fun Density.scrubTrackRect(barW: Float, barH: Float, s: Float): Rect {
    val from = hairlineRect(barW, barH)
    val tw = TrackWidth.toPx()
    val th = TrackHeight.toPx()
    val to = Rect(Offset((barW - tw) / 2f, barH - TrackBottom.toPx() - th), Size(tw, th))
    return Rect(lerpF(from.left, to.left, s), lerpF(from.top, to.top, s), lerpF(from.right, to.right, s), lerpF(from.bottom, to.bottom, s))
}

/** Tool [i] of [n]'s spot in the row (its centre), from the resting bar's bottom centre; spread evenly. */
private fun Density.rowSpot(i: Int, n: Int): Offset {
    val step = ToolRowWidth.toPx() / n
    return Offset(-ToolRowWidth.toPx() / 2f + step * (i + 0.5f), -ToolRowCenter.toPx())
}

/**
 * How open the card is when tool [i] starts to pop in: once the card's top has grown past most of
 * the row, then left to right.
 */
private fun toolRevealAt(i: Int): Float = (0.55f + 0.05f * i).coerceAtMost(0.8f)

/** The tool under [at] (from the resting bar's bottom centre), within reach of its circle, or -1. */
private fun Density.toolAt(at: Offset, n: Int): Int {
    var best = -1
    var bestD = PetalSize.toPx() * 0.75f
    for (i in 0 until n) {
        val c = rowSpot(i, n)
        val d = hypot(c.x - at.x, c.y - at.y)
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

private fun haptic(view: View, kind: Int) { view.performHapticFeedback(kind) }

/**
 * Every finger on the bar, seen first (Initial pass) so a gesture the bar takes over cancels the
 * buttons' clicks underneath:
 *  - sideways on play: scrub (the bar grows into the chapter track, the knob stays under the finger);
 *  - up / down: opens / closes the card, or closes the open panel, following the finger 1:1;
 *  - hold (at rest): the card blooms; slide onto a tool and let go to pick it.
 * The bar resizes under the finger as it goes, so movement is summed from each event's own
 * change (which is in the bar's current coordinates) rather than measured from the first touch.
 */
internal fun Modifier.playerBarGestures(
    bar: PlayerBarState,
    player: PlayerState,
    enabled: Boolean,
    view: View,
    gate: () -> Float,
    onPick: (BarTool) -> Unit,
): Modifier = this.then(
    if (!enabled) Modifier else Modifier.pointerInput(bar, player) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val restH = PlayerBarHeight.toPx()
            val cardOpen = bar.card.targetValue >= 0.5f
            val panelOpen = bar.panel.targetValue >= 0.5f
            val atRest = !cardOpen && !panelOpen && gate() >= 1f
            val onPlay = atRest && playRect(size.width.toFloat(), size.height.toFloat()).contains(down.position)
            val startCard = bar.card.value
            val startPanel = bar.panel.value
            val cardTravel = CardHeight.toPx() - restH
            val panelTravel = panelSize(bar.panelKind, player).height - restH
            val duration = player.durationMs
            val trackPx = TrackWidth.toPx()
            // The knob sits under the thumb: the track (centred in the bar) maps straight onto the video.
            fun landingAt(x: Float): Long = (((x - size.width / 2f) / trackPx + 0.5f).coerceIn(0f, 1f) * duration).toLong()
            // The chapter list scrolls under the finger; pulled down from its top it closes like the panel.
            val chapterList = bar.chapterList.takeIf { panelOpen && bar.panelKind == BarPanel.Chapters }
            val inList = chapterList != null && down.position.y in chaptersListSpan(size.height.toFloat(), player)
            val tracker = VelocityTracker()
            var moved = Offset.Zero
            tracker.addPosition(down.uptimeMillis, moved)
            val longPressAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
            var lastAt = down.uptimeMillis
            // 0 undecided, 1 scrub, 2 vertical, 3 held (the card opens under the finger), 4 not ours.
            var mode = 0
            var chapter: String? = null
            try {
                while (true) {
                    val event = if (mode == 0 && atRest) {
                        withTimeoutOrNull((longPressAt - lastAt).coerceAtLeast(1L)) { awaitPointerEvent(PointerEventPass.Initial) }
                    } else {
                        awaitPointerEvent(PointerEventPass.Initial)
                    }
                    if (event == null) {
                        // Held still: bloom under the finger.
                        mode = 3
                        haptic(view, HapticFeedbackConstants.LONG_PRESS)
                        bar.openCard()
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    lastAt = change.uptimeMillis
                    moved += change.position - change.previousPosition
                    tracker.addPosition(change.uptimeMillis, moved)
                    if (change.changedToUpIgnoreConsumed()) {
                        if (mode != 0 && mode != 4) change.consume()
                        when (mode) {
                            1 -> {
                                val cancelled = bar.cancelArmed
                                if (cancelled) haptic(view, HapticFeedbackConstants.CONTEXT_CLICK)
                                bar.endScrub(player, cancelled)
                            }
                            2 -> {
                                val vy = tracker.calculateVelocity().y
                                if (panelOpen) bar.settle(bar.panel, -vy / panelTravel, wasOpen = true)
                                else bar.settle(bar.card, -vy / cardTravel, wasOpen = cardOpen)
                            }
                            3 -> {
                                val picked = bar.hot
                                bar.hot = null
                                if (picked != null) { haptic(view, HapticFeedbackConstants.CONTEXT_CLICK); onPick(picked) }
                            }
                        }
                        break
                    }
                    if (!change.pressed) break
                    if (mode == 0 && hypot(moved.x, moved.y) > viewConfiguration.touchSlop) {
                        mode = when {
                            onPlay && abs(moved.x) > abs(moved.y) && duration > 0 -> 1
                            inList && !(moved.y > abs(moved.x) && chapterList?.canScrollBackward == false) -> 4
                            abs(moved.y) > abs(moved.x) -> 2
                            else -> 4
                        }
                        if (mode == 1) {
                            val ms = landingAt(change.position.x)
                            bar.previewMs = ms
                            chapter = player.chapterAt(ms)
                            bar.animate(bar.scrub, 1f)
                        }
                    }
                    when (mode) {
                        1 -> {
                            change.consume()
                            // Swiping up brings out Cancel, which arms only once the finger is on it (its
                            // bottom edge, in the bar's own pixels: above the top, so negative). Armed, the
                            // landing spot holds still (sideways moves are ignored); otherwise it's under the thumb.
                            val y = change.position.y
                            val reach = -cancelRaise(player).toPx()
                            val armed = if (bar.cancelArmed) y < reach + CancelSlack.toPx() else y <= reach
                            if (armed != bar.cancelArmed) {
                                bar.cancelArmed = armed
                                haptic(view, if (armed) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK)
                            }
                            // Fading in, measured up from the bar's bottom (which stays put while the bar grows
                            // into the track): from CancelFrom over the track's middle to Cancel itself.
                            val up = size.height - y
                            val from = (TrackBottom + TrackHeight / 2 + CancelFrom).toPx()
                            val to = size.height - reach
                            bar.snap(bar.cancel, if (armed) 1f else ((up - from) / (to - from).coerceAtLeast(1f)).coerceIn(0f, 0.999f))
                            if (!armed) {
                                val ms = landingAt(change.position.x)
                                bar.previewMs = ms
                                val now = player.chapterAt(ms)
                                if (now != chapter) { chapter = now; haptic(view, HapticFeedbackConstants.CLOCK_TICK) }
                            }
                        }
                        2 -> {
                            change.consume()
                            if (panelOpen) bar.snap(bar.panel, (startPanel - moved.y / panelTravel).coerceIn(0f, 1f))
                            else bar.snap(bar.card, (startCard - moved.y / cardTravel).coerceIn(0f, 1.04f))
                        }
                        3 -> {
                            change.consume()
                            // The card may still be opening: aim at where the tools land, measured
                            // from the resting bar's bottom centre (the card's time row hangs below it).
                            val restBottom = size.height - playerBarBelow(bar, gate())
                            val at = Offset(change.position.x - size.width / 2f, change.position.y - restBottom)
                            val tools = bar.tools
                            val tool = toolAt(at, tools.size).takeIf { it >= 0 }?.let { tools[it] }
                            if (tool != bar.hot) { bar.hot = tool; if (tool != null) haptic(view, HapticFeedbackConstants.CLOCK_TICK) }
                        }
                    }
                }
            } finally {
                // A scrub the finger never let go of (the pointer vanished, the bar went away) is dropped.
                if (mode == 1 && bar.previewMs != null) bar.endScrub(player, cancelled = true)
            }
        }
    },
)

/** The tools' and side buttons' own tone: a step lighter than the bar. */
@Composable
private fun barTile(): Color {
    val c = MaterialTheme.colorScheme
    return c.surface.copy(alpha = 0.62f).compositeOver(c.surfaceVariant)
}

/** ⋮ at rest, ✕ while the card or a panel is open; turns as it opens. */
@Composable
internal fun BarKey(bar: PlayerBarState, enabled: Boolean, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val tile = barTile()
    val open = { maxOf(bar.card.value, bar.panel.value).coerceIn(0f, 1f) }
    Box(
        modifier
            .size(SideSlotWidth)
            .pressable(enabled = enabled) {
                haptic(view, HapticFeedbackConstants.CONTEXT_CLICK)
                if (bar.isOpen) bar.closeAll() else bar.openCard()
            }
            .clip(CircleShape)
            .background(tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.MoreVert, "More controls", tint = c.onSurface,
            modifier = Modifier.size(21.dp).graphicsLayer {
                val t = open()
                alpha = 1f - t
                rotationZ = 90f * t
                scaleX = 1f - 0.5f * t; scaleY = scaleX
            },
        )
        Icon(
            Icons.Rounded.Close, "Close", tint = c.onSurface,
            modifier = Modifier.size(21.dp).graphicsLayer {
                val t = open()
                alpha = t
                rotationZ = -90f * (1f - t)
                scaleX = 0.5f + 0.5f * t; scaleY = scaleX
            },
        )
    }
}

/** Audio only (app-wide), at the bar's left so play sits dead centre; filled while on. */
@Composable
internal fun BarAudioOnly(player: PlayerState, enabled: Boolean, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val on = AudioOnly.enabled
    Box(
        modifier
            .squishOn(on)
            .size(SideSlotWidth)
            .pressable(enabled = enabled) {
                haptic(view, HapticFeedbackConstants.CONTEXT_CLICK)
                player.setAudioOnly(!on)
            }
            .clip(CircleShape)
            .background(if (on) c.primary else barTile()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (on) Icons.Rounded.Headphones else Icons.Outlined.Headphones,
            if (on) "Audio only is on" else "Audio only",
            tint = if (on) c.onPrimary else c.onSurface, modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * The card's contents: the tools in a row over the transport row, and the chapter · time under the
 * progress line (in the strip the card grows below the resting bar). Each tool waits at its own spot
 * and pops in once the card has grown past it, left to right, so nothing crosses play.
 */
@Composable
internal fun BarCard(bar: PlayerBarState, player: PlayerState, gate: () -> Float, onPick: (BarTool) -> Unit) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val tile = barTile()
    val tools = bar.tools
    val live = bar.cardLive
    Box(Modifier.fillMaxSize()) {
        // Chapter · time, under the line: this box ends at the resting bar's bottom; the card reaches cardBelow further.
        // With chapters it's a button (the very bottom of the card) that opens the chapter list.
        val below = bar.cardBelow()
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = below - 2.dp)
                .height(below)
                .widthIn(max = ToolRowWidth)
                .graphicsLayer { alpha = ramp(bar.card.value * gate(), 0.55f, 1f) },
            contentAlignment = Alignment.Center,
        ) {
            val chapters = bar.hasChapters
            Row(
                Modifier.then(
                    if (!chapters) Modifier else Modifier
                        .padding(bottom = 6.dp)
                        .height(ChaptersButtonHeight)
                        .pressable(enabled = live) {
                            haptic(view, HapticFeedbackConstants.CONTEXT_CLICK)
                            bar.openPanel(BarPanel.Chapters)
                        }
                        .clip(CircleShape)
                        .background(tile)
                        .padding(start = 12.dp, end = 10.dp),
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chapters) {
                    Icon(Icons.AutoMirrored.Rounded.List, null, tint = c.onSurface, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                player.chapterAt(player.positionMs)?.let {
                    Text(
                        it, color = c.onSurface, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(" · ", color = c.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Text(
                    "${formatDuration(player.positionMs)} / ${formatDuration(player.durationMs)}",
                    color = c.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
                if (chapters) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.ExpandLess, "Chapters", tint = c.onSurface, modifier = Modifier.size(20.dp))
                }
            }
        }
        Layout(
            content = {
                tools.forEach { tool ->
                    val hot = bar.hot == tool
                    val sticky = tool == BarTool.Speed && PlaybackSpeed.remember && !PlaybackSpeed.isNormal(player.speed)
                    val filled = hot || sticky
                    Column(
                        Modifier
                            .size(PetalSize)
                            .pressable(enabled = live) { haptic(view, HapticFeedbackConstants.CONTEXT_CLICK); onPick(tool) }
                            .clip(CircleShape)
                            .background(if (filled) c.primary else tile),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        val ink = if (filled) c.onPrimary else c.onSurface
                        val sub = if (filled) c.onPrimary else c.onSurfaceVariant
                        val glyph = Modifier.size(21.dp)
                        when (tool) {
                            BarTool.Speed -> Text(PlaybackSpeed.label(player.speed) + "×", color = ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.height(21.dp))
                            BarTool.Quality -> Text(QualityPreference.height?.let { "${it}p" } ?: "Auto", color = ink, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.height(21.dp))
                            BarTool.Pip -> Icon(Icons.Rounded.PictureInPictureAlt, null, tint = ink, modifier = glyph)
                            BarTool.Fullscreen -> Icon(Icons.Rounded.Fullscreen, null, tint = ink, modifier = glyph)
                            BarTool.Next -> Icon(Icons.Rounded.SkipNext, null, tint = ink, modifier = glyph)
                        }
                        Text(
                            when (tool) {
                                BarTool.Speed -> "Speed"; BarTool.Quality -> "Quality"; BarTool.Pip -> "PiP"
                                BarTool.Fullscreen -> "Fullscreen"; BarTool.Next -> "Next"
                            },
                            color = sub, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val petal = PetalSize.roundToPx()
            val placeables = measurables.map { it.measure(Constraints.fixed(petal, petal)) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val t = bar.card.value * gate()
                val n = placeables.size
                placeables.forEachIndexed { i, p ->
                    val at = rowSpot(i, n) + Offset(w / 2f, h)
                    val from = toolRevealAt(i)
                    val k = ramp(t, from, from + 0.16f)
                    val hot = bar.hot == tools.getOrNull(i)
                    p.placeWithLayer((at.x - petal / 2f).roundToInt(), (at.y - petal / 2f).roundToInt()) {
                        alpha = k
                        translationY = 8.dp.toPx() * (1f - k)
                        val s = 0.6f + 0.4f * Motion.Pop.transform(k)
                        scaleX = if (hot) s * 1.15f else s
                        scaleY = scaleX
                    }
                }
            }
        }
    }
}

/**
 * The progress bar (0.5.37, Derek picked mockup A "Inside"): a hairline inside the bar under the
 * transport row — chapters as segments, SponsorBlock marks in their colours, filling as the video
 * plays. Scrubbing swells that same line into the big chapter track (SponsorBlock marks along its
 * bottom, a knob under the finger) with the chapter and time across the top, and back.
 */
@Composable
internal fun BarProgress(bar: PlayerBarState, player: PlayerState, played: Animatable<Float, *>, gate: () -> Float) {
    val c = MaterialTheme.colorScheme
    val track = c.primary.copy(alpha = 0.3f).compositeOver(c.surfaceVariant)
    val trackPath = remember { Path() }
    val shownMs = bar.previewMs ?: player.positionMs
    val segments = if (SponsorBlock.enabled) player.segments.filter { it.action != SponsorBlock.Action.Off } else emptyList()
    val chapters = player.details?.chapters.orEmpty()
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val g = gate()
                if (g <= 0f || player.durationMs <= 0L) return@drawBehind
                val s = (bar.scrub.value * g).coerceAtLeast(0f)
                val seen = s.coerceIn(0f, 1f)
                val dur = player.durationMs.toFloat()
                val r = scrubTrackRect(size.width, size.height, s)
                val corner = lerpF(HairlineHeight.toPx() / 2f, 20.dp.toPx(), seen)
                // Where playback is now, and (scrubbing) where it would land, under the thumb.
                val now = played.value.coerceIn(0f, 1f)
                val f = (bar.previewMs?.let { it / dur } ?: now).coerceIn(0f, 1f)
                val alpha = ramp(g, 0.4f, 1f)
                trackPath.reset()
                trackPath.addRoundRect(RoundRect(r, CornerRadius(corner)))
                clipPath(trackPath) {
                    drawRect(track, r.topLeft, r.size, alpha = alpha)
                    // Played is solid up to now; the stretch a scrub would jump over (either way) is a
                    // paler primary, so the edge between them reads as "you are here".
                    val lo = minOf(now, f)
                    drawRect(c.primary, r.topLeft, Size(r.width * lo, r.height), alpha = alpha)
                    if (f != now) drawRect(c.primary, Offset(r.left + r.width * lo, r.top), Size(r.width * abs(f - now), r.height), alpha = alpha * 0.45f)
                    // SponsorBlock: the whole line's height at rest, a strip along the track's bottom scrubbing.
                    val markH = lerpF(r.height, 5.dp.toPx(), seen)
                    val markBottom = lerpF(r.bottom, r.bottom - 5.dp.toPx(), seen)
                    segments.forEach { seg ->
                        val x0 = r.left + r.width * (seg.startMs / dur)
                        val x1 = r.left + r.width * (seg.endMs / dur)
                        drawRoundRect(
                            seg.category.color, Offset(x0, markBottom - markH), Size((x1 - x0).coerceAtLeast(2.dp.toPx()), markH),
                            CornerRadius(markH / 2f), alpha = alpha,
                        )
                    }
                    // Chapter edges cut through as slits of the bar's tone.
                    val slit = lerpF(2.dp.toPx(), 3.dp.toPx(), seen)
                    chapters.forEach { ch ->
                        if (ch.startMs <= 0) return@forEach
                        val x = r.left + r.width * (ch.startMs / dur)
                        drawRect(c.surfaceVariant, Offset(x - slit / 2f, r.top), Size(slit, r.height))
                    }
                }
                // While scrubbing: "now" is a primary dot over the track (ringed in the bar's tone), and the
                // knob — the landing spot — a line through the track under the thumb.
                if (seen > 0f) {
                    val nx = r.left + r.width * now
                    val ny = r.top - 10.dp.toPx()
                    // A pin: the dot on a thin stem down through the track.
                    drawRect(c.surfaceVariant, Offset(nx - 2.75.dp.toPx(), ny), Size(5.5.dp.toPx(), r.top - ny), alpha = seen)
                    drawRect(c.primary, Offset(nx - 1.25.dp.toPx(), ny), Size(2.5.dp.toPx(), r.bottom - ny), alpha = seen)
                    drawCircle(c.surfaceVariant, radius = 7.5.dp.toPx() * seen, center = Offset(nx, ny))
                    drawCircle(c.primary, radius = 5.dp.toPx() * seen, center = Offset(nx, ny))
                    val kx = r.left + r.width * f
                    drawRoundRect(
                        c.onSurface.copy(alpha = seen),
                        Offset(kx - 2.dp.toPx(), r.top - 6.dp.toPx()), Size(4.dp.toPx(), r.height + 12.dp.toPx()),
                        CornerRadius(2.dp.toPx()),
                    )
                }
            },
    ) {
        if (bar.scrubShown) Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
                .width(TrackWidth)
                .graphicsLayer {
                    val s = (bar.scrub.value * gate()).coerceIn(0f, 1f)
                    alpha = ramp(s, 0.3f, 1f)
                    translationY = -10.dp.toPx() * (1f - s)
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The time on the left, the chapter on the right (Derek: a right thumb scrubbing hid the time).
            Text(
                "${formatDuration(shownMs)} / ${formatDuration(player.durationMs)}",
                color = c.onSurfaceVariant, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
            )
            // How far from now the scrub would jump.
            val jump = (bar.previewMs ?: player.positionMs) - player.positionMs
            if (abs(jump) >= 1000L) Text(
                (if (jump > 0) "+" else "−") + formatDuration(abs(jump)),
                color = c.primary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                player.chapterAt(shownMs).orEmpty(), color = c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
        }
    }
}

/** The open panel, above the transport row: the quality list or the speed ruler. */
@Composable
internal fun BarPanelContent(bar: PlayerBarState, player: PlayerState, gate: () -> Float, scope: CoroutineScope, sheets: StoryboardSheets) {
    when (bar.panelKind) {
        BarPanel.Quality -> BarQualityList(bar, player, gate, scope)
        BarPanel.Chapters -> BarChaptersList(bar, player, gate, scope, sheets)
        BarPanel.Speed -> Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = PlayerBarHeight + 6.dp)
                    .width(SpeedWidth - 24.dp)
                    .height(SpeedPanelHeight)
                    .graphicsLayer {
                        val p = (bar.panel.value * gate()).coerceIn(0f, 1f)
                        alpha = ramp(p, 0.4f, 1f)
                        translationY = 16.dp.toPx() * (1f - Motion.Emphasized.transform(ramp(p, 0.3f, 1f)))
                    },
            ) {
                SpeedPanelContent(player, inBar = true)
            }
        }
    }
}

/**
 * The quality list inside the bar: Auto (and what it's playing now) and every height, the
 * current one filled with a check. Rows rise in from the bottom as the list opens.
 */
@Composable
private fun BarQualityList(bar: PlayerBarState, player: PlayerState, gate: () -> Float, scope: CoroutineScope) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val details = player.details ?: return
    val rows = listOf<Int?>(null) + details.qualities
    var picked by remember(details.item.id) { mutableStateOf(QualityPreference.height) }
    val live = bar.panelLive
    val rowsHeight = (ListRowHeight + ListRowGap) * rows.size.coerceAtMost(ListMaxRows)
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                // Pinned just above the rows, so a closing list clips it rather than sliding it over them.
                .padding(bottom = PlayerBarHeight + 6.dp + rowsHeight + 10.dp)
                .width(QualityWidth - 36.dp)
                .graphicsLayer {
                    val l = (bar.panel.value * gate()).coerceIn(0f, 1f)
                    alpha = ramp(l, 0.5f, 1f)
                    translationY = 10.dp.toPx() * (1f - l)
                },
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("Quality", color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            (player.decodedHeight?.let { "Playing ${it}p" } ?: details.quality?.let { "Playing $it" })?.let {
                Text(it, color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = PlayerBarHeight + 6.dp)
                .width(QualityWidth - 24.dp)
                .height(rowsHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(ListRowGap),
        ) {
            rows.forEachIndexed { index, h ->
                val selected = picked == h
                val fromBottom = rows.size - 1 - index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(ListRowHeight)
                        .graphicsLayer {
                            val k = ramp(bar.panel.value * gate(), 0.12f + fromBottom * 0.05f, 0.62f + fromBottom * 0.05f)
                            alpha = k
                            translationY = 18.dp.toPx() * (1f - Motion.Emphasized.transform(k))
                        }
                        .pressable(enabled = live, pressedScale = 0.97f) {
                            haptic(view, HapticFeedbackConstants.CONTEXT_CLICK)
                            picked = h
                            player.setQuality(h)
                            // The check lands, then the bar folds back down.
                            scope.launch { delay(QualityPickedMs); bar.closeAll() }
                        }
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) c.primary else c.primary.copy(alpha = 0f))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ink = if (selected) c.onPrimary else c.onSurface
                    Text(h?.let { "${it}p" } ?: "Auto", color = ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    val note = if (h == null) (player.decodedHeight ?: details.quality?.removeSuffix("p")?.toIntOrNull())?.let { " · ${it}p now" }
                    else qualityNote(h)?.let { " · $it" }
                    if (note != null) {
                        Text(note, color = if (selected) c.onPrimary.copy(alpha = 0.8f) else c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Rounded.Check, if (selected) "Selected" else null, tint = c.onPrimary,
                        modifier = Modifier.size(20.dp).graphicsLayer { alpha = if (selected) 1f else 0f },
                    )
                }
            }
        }
    }
}

/**
 * Every chapter in the bar (from the card's Chapters button): a storyboard frame from its start, its
 * title and start time, the one playing now filled. Opens scrolled to the current chapter; a tap
 * jumps to that chapter's start, the fill moves there, then the bar folds back down.
 */
@Composable
private fun BarChaptersList(bar: PlayerBarState, player: PlayerState, gate: () -> Float, scope: CoroutineScope, sheets: StoryboardSheets) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val details = player.details ?: return
    val chapters = details.chapters
    val current = chapters.indexOfLast { it.startMs <= player.positionMs }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    DisposableEffect(state) {
        bar.chapterList = state
        onDispose { if (bar.chapterList === state) bar.chapterList = null }
    }
    val live = bar.panelLive
    val rowsHeight = chapterRowsHeight(player)
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                // Pinned just above the rows, so a closing list clips it rather than sliding it over them.
                .padding(bottom = PlayerBarHeight + 6.dp + rowsHeight + 10.dp)
                .width(ChaptersWidth - 36.dp)
                .graphicsLayer {
                    val l = (bar.panel.value * gate()).coerceIn(0f, 1f)
                    alpha = ramp(l, 0.5f, 1f)
                    translationY = 10.dp.toPx() * (1f - l)
                },
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("Chapters", color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text("${current + 1} of ${chapters.size}", color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            state = state,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = PlayerBarHeight + 6.dp)
                .width(ChaptersWidth - 24.dp)
                .height(rowsHeight),
            verticalArrangement = Arrangement.spacedBy(ListRowGap),
        ) {
            itemsIndexed(chapters) { index, chapter ->
                val selected = index == current
                val fromBottom = (chapters.size - 1 - index).coerceAtMost(ChapterMaxRows)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(ChapterRowHeight)
                        .graphicsLayer {
                            val k = ramp(bar.panel.value * gate(), 0.12f + fromBottom * 0.04f, 0.62f + fromBottom * 0.04f)
                            alpha = k
                            translationY = 18.dp.toPx() * (1f - Motion.Emphasized.transform(k))
                        }
                        .pressable(enabled = live, pressedScale = 0.97f) {
                            haptic(view, HapticFeedbackConstants.CONTEXT_CLICK)
                            player.seekTo(chapter.startMs)
                            // The fill lands on the picked chapter, then the bar folds back down.
                            scope.launch { delay(QualityPickedMs); bar.closeAll() }
                        }
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) c.primary else c.primary.copy(alpha = 0f))
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StoryboardThumb(
                        sheets, details.storyboards, chapter.startMs, player.durationMs,
                        Modifier.size(width = 80.dp, height = 45.dp).clip(RoundedCornerShape(12.dp)).background(barTile()),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
                        Text(
                            chapter.title.ifBlank { "Chapter ${index + 1}" }, color = if (selected) c.onPrimary else c.onSurface,
                            fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatDuration(chapter.startMs), color = if (selected) c.onPrimary.copy(alpha = 0.8f) else c.onSurfaceVariant,
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private fun qualityNote(height: Int): String? = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "QHD"
    height >= 1080 -> "Full HD"
    height >= 720 -> "HD"
    height <= 240 -> "Data saver"
    else -> null
}

/** Runs a picked tool: speed and quality open their panel; the others close the card and act. */
internal fun pickTool(tool: BarTool, bar: PlayerBarState, player: PlayerState, pip: PipController?, onFullscreen: () -> Unit) {
    when (tool) {
        BarTool.Speed -> bar.openPanel(BarPanel.Speed)
        BarTool.Quality -> bar.openPanel(BarPanel.Quality)
        BarTool.Pip -> {
            bar.closeAll()
            if (pip?.enter(player.videoAspect) != true) player.actionMessage = "Picture-in-picture is turned off for Skipii in Settings."
        }
        BarTool.Fullscreen -> { bar.closeAll(); onFullscreen() }
        BarTool.Next -> { bar.closeAll(); player.playNextUp() }
    }
}

/** The scrub preview fits its frame's shape inside this box, this far above the bar. */
private val PreviewMaxWidth = 184.dp
private val PreviewMaxHeight = 132.dp
private val PreviewGap = 12.dp
private val PreviewCorner = 16.dp
/** Cancel sits this far above the preview (or the bar, without one). */
private val CancelHeight = 46.dp
private val CancelGap = 10.dp

private fun previewSize(board: Storyboard): DpSize {
    val aspect = board.frameWidth.toFloat() / board.frameHeight
    val w = minOf(PreviewMaxWidth, PreviewMaxHeight * aspect)
    return DpSize(w, w / aspect)
}

/** How far above the bar's top Cancel's bottom edge sits: over the preview frame when there is one. */
private fun cancelRaise(player: PlayerState): Dp =
    player.details?.storyboards?.lastOrNull()?.let { PreviewGap + previewSize(it).height + CancelGap } ?: CancelGap

/** Storyboard sheets kept decoded at once (each up to ~1000 × 600 px); the rest reload from Coil's disk cache. */
private const val SheetsKept = 24
/** The quick level's sheets fetched as a scrub starts, so a frame is there wherever the finger goes. */
private const val QuickSheetsPrefetched = 12

/**
 * Storyboard sheets for the scrub preview, fetched through Coil (its disk cache keeps them) and held
 * decoded while recently used. [loaded] is state, so the preview redraws as a sheet arrives.
 */
internal class StoryboardSheets(private val scope: CoroutineScope, private val context: Context) {
    val loaded = mutableStateMapOf<String, ImageBitmap>()
    private val asked = LinkedHashSet<String>()

    fun request(url: String) {
        if (url in asked) return
        asked += url
        // Oldest first out: an evicted sheet can be asked for again.
        while (asked.size > SheetsKept) asked.first().let { asked.remove(it); loaded.remove(it) }
        scope.launch {
            try {
                val request = ImageRequest.Builder(context).data(url).size(CoilSize.ORIGINAL).build()
                val bitmap = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null && url in asked) loaded[url] = bitmap.asImageBitmap()
            } finally {
                // Failed or cancelled: forget it, so the next scrub over it tries again.
                if (url !in loaded) asked.remove(url)
            }
        }
    }
}

/** The current video's storyboard sheets, shared by the scrub preview and the chapter list. */
@Composable
internal fun rememberStoryboardSheets(player: PlayerState): StoryboardSheets {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(player.details?.item?.id) { StoryboardSheets(scope, context) }
}

/** Draws [frame] (a spot in [image], a storyboard sheet) filling the whole draw area. */
private fun DrawScope.drawStoryboardFrame(image: ImageBitmap, frame: StoryboardFrame) {
    val x = frame.x.coerceIn(0, (image.width - 1).coerceAtLeast(0))
    val y = frame.y.coerceIn(0, (image.height - 1).coerceAtLeast(0))
    drawImage(
        image,
        srcOffset = IntOffset(x, y),
        srcSize = IntSize(frame.width.coerceAtMost(image.width - x), frame.height.coerceAtMost(image.height - y)),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

/** The storyboard frame at [ms] (the sharpest level), drawn once its sheet is in; [modifier]'s own background until then. */
@Composable
private fun StoryboardThumb(sheets: StoryboardSheets, boards: List<Storyboard>, ms: Long, durationMs: Long, modifier: Modifier) {
    val frame = boards.lastOrNull()?.frameAt(ms, durationMs.coerceAtLeast(1L))
    LaunchedEffect(frame?.url) { frame?.url?.let(sheets::request) }
    val image = frame?.let { sheets.loaded[it.url] }
    Box(modifier.drawBehind { if (image != null && frame != null) drawStoryboardFrame(image, frame) })
}

/**
 * Above the scrub track, outside the bar's clip (NavPill places it in its slot, [barRect] being the
 * bar's bounds there): the frame the scrub would land on, from YouTube's storyboard, riding over the
 * knob; and, above it, Cancel, fading in as the finger swipes up off the track while the frame washes
 * out. Cancel arms only once the finger reaches it; let go there and the scrub is dropped (no seek).
 * Without a storyboard only Cancel shows, just over the bar.
 */
@Composable
internal fun ScrubOverlay(bar: PlayerBarState, player: PlayerState, sheets: StoryboardSheets, gate: () -> Float, barRect: () -> Rect?) {
    val c = MaterialTheme.colorScheme
    val details = player.details
    // The last frame shown: the preview keeps it while it fades away after the scrub ends.
    val last = remember { arrayOfNulls<Any>(2) }
    if (!bar.scrubShown) return
    val duration = player.durationMs.coerceAtLeast(1L)
    val boards = details?.storyboards.orEmpty()
    val best = boards.lastOrNull()
    // A smaller level with few sheets: fetched whole up front, shown while the sharp sheet loads.
    val quick = boards.firstOrNull { it.frameWidth >= 80 && it !== best }
    LaunchedEffect(quick) { quick?.urls?.take(QuickSheetsPrefetched)?.forEach(sheets::request) }
    val ms = bar.previewMs ?: (last[0] as Long?) ?: player.positionMs
    last[0] = ms
    val sharp = best?.frameAt(ms, duration)
    LaunchedEffect(sharp?.url) { sharp?.url?.let(sheets::request) }
    val shown = sharp?.let { f -> sheets.loaded[f.url]?.let { it to f } }
        ?: quick?.frameAt(ms, duration)?.let { f -> sheets.loaded[f.url]?.let { it to f } }
    if (shown != null) last[1] = shown
    @Suppress("UNCHECKED_CAST")
    val frame = shown ?: last[1] as Pair<ImageBitmap, StoryboardFrame>?
    val scrubAmount = { (bar.scrub.value * gate()).coerceIn(0f, 1f) }
    val size = best?.let { previewSize(it) }
    /** The preview's left edge in the slot: centred over the knob, kept within the bar's width. */
    val previewLeft: Density.(Rect, Float) -> Float = { b, w ->
        val track = scrubTrackRect(b.width, b.height, scrubAmount())
        val knob = b.left + track.left + track.width * (ms.toFloat() / duration).coerceIn(0f, 1f)
        (knob - w / 2f).coerceIn(b.left, (b.right - w).coerceAtLeast(b.left))
    }

    // Each piece is ONE layer holding its own shape, clip, shadow and fade: a fade on an outer layer
    // around a separate shadow layer crops that shadow to a hard-edged box (Derek's 6434.png).
    if (size != null) {
        val shape = RoundedCornerShape(PreviewCorner)
        Box(
            Modifier
                .layout { measurable, _ ->
                    val p = measurable.measure(Constraints.fixed(size.width.roundToPx(), size.height.roundToPx()))
                    layout(0, 0) {
                        val b = barRect() ?: return@layout
                        p.place(previewLeft(b, p.width.toFloat()).roundToInt(), (b.top - PreviewGap.toPx() - p.height).roundToInt())
                    }
                }
                .graphicsLayer {
                    val s = scrubAmount()
                    alpha = ramp(s, 0.3f, 1f)
                    translationY = 10.dp.toPx() * (1f - s)
                    val k = 0.88f + 0.12f * Motion.Emphasized.transform(s)
                    scaleX = k; scaleY = k
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    this.shape = shape
                    clip = true
                    shadowElevation = 12.dp.toPx()
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                }
                .background(c.surfaceVariant)
                .drawBehind {
                    val (image, f) = frame ?: return@drawBehind
                    drawStoryboardFrame(image, f)
                }
                // Heading for Cancel, the frame washes out toward the bar's tone (an opaque wash, so
                // the page never shows through it).
                .drawWithContent {
                    drawContent()
                    drawRect(c.surfaceVariant, alpha = 0.6f * bar.cancel.value.coerceIn(0f, 1f))
                },
        )
    }

    // Cancel, over the preview: a tone pill fading in as the finger heads up, full primary once it's on it.
    val armed = bar.cancelArmed
    val fill by animateColorAsState(if (armed) c.primary else c.surfaceContainerHighest, tween(140, easing = Motion.Emphasized), label = "cancelFill")
    val ink by animateColorAsState(if (armed) c.onPrimary else c.onSurface, tween(140, easing = Motion.Emphasized), label = "cancelInk")
    val pop by animateFloatAsState(if (armed && !bar.reduced) 1.08f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "cancelPop")
    Row(
        Modifier
            .layout { measurable, _ ->
                val p = measurable.measure(Constraints())
                layout(0, 0) {
                    val b = barRect() ?: return@layout
                    val centre = if (size != null) previewLeft(b, size.width.toPx()) + size.width.toPx() / 2f else b.center.x
                    val x = (centre - p.width / 2f).coerceIn(b.left, (b.right - p.width).coerceAtLeast(b.left))
                    p.place(x.roundToInt(), (b.top - cancelRaise(player).toPx() - p.height).roundToInt())
                }
            }
            .graphicsLayer {
                val k = bar.cancel.value.coerceIn(0f, 1f) * ramp(scrubAmount(), 0.3f, 1f)
                alpha = ramp(k, 0f, 0.4f)
                translationY = 12.dp.toPx() * (1f - Motion.Emphasized.transform(k))
                val s = (0.7f + 0.3f * Motion.Pop.transform(k)) * pop
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
                shape = CircleShape
                clip = true
                shadowElevation = 12.dp.toPx()
                ambientShadowColor = Color.Black
                spotShadowColor = Color.Black
            }
            .background(fill)
            .height(CancelHeight)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Close, null, tint = ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Cancel", color = ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}
