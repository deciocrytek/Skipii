package com.decio.skipii

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// Castii's floating stack: a nav pill capped at 328 dp, the mini-player 8 dp above it.
// 62 dp (Derek, 2026-10-05): between Castii's 56 and Ledgii's 78. Tabs keep 50 dp targets.
internal val NavHeight = 62.dp
internal val NavWidth = 328.dp
private const val SELECTED_TAB_WEIGHT = 2.2f
// The open player's bar is compact (Derek, 2026-10-19: play pill ~60% of its first 161 dp,
// the ±10 buttons tight beside it): it narrows from the tab bar's width as the player opens.
private val PlaySlotWidth = 96.dp
private val SeekSlotWidth = 50.dp
private val BarPadding = 6.dp
private val BarGap = 4.dp
private val CompactBarWidth = BarPadding * 2 + SeekSlotWidth * 2 + PlaySlotWidth + BarGap * 2

// Where each part of the bar's morph happens along PlayerMorph.progress (tuning dials).
private const val TabExitStagger = 0.04f
private const val TabExitSpan = 0.25f
private const val PillTravelStart = 0.06f
private const val PillTravelEnd = 0.7f
private const val TrackFadeStart = 0.5f
private const val TrackFadeEnd = 0.95f
private const val PlayEnterStart = 0.28f
private const val SeekEnterStart = 0.34f
private const val ControlEnterSpan = 0.45f
private const val NarrowStart = 0.1f
private const val NarrowEnd = 0.75f
/** A burst of ±10 taps stays open this long after its last tap; further taps add to it. */
private const val BarBurstWindowMs = 700L

/**
 * Castii's floating nav pill, which is also the open player's own bar (Derek, 2026-10-19:
 * mockup A in docs/design/player-bar.html, without its next button and time label).
 *
 * Tabs: tonal surfaceVariant with a wide, very soft shadow; the active tab grows (weight
 * 2.2) and reveals its label on a full-primary pill. No ripple, no outline.
 *
 * Player: as the player opens, the same capsule narrows into a compact −10 · play · +10. Everything
 * follows [PlayerMorph.progress] (read only in layer/draw lambdas), so a swipe down scrubs
 * it back into tabs under the finger: the tabs drop out left to right, the selected tab's
 * pill glides (with a little stretch) into the middle slot and becomes Play, and the
 * buttons spring up from below, play first. The play pill then fills with how much of the
 * video has played: a pale primary track, a full-primary fill, the glyph two-toned across
 * the edge. [controlsActive] hands the touches from the tabs to the player buttons.
 */
@Composable
internal fun NavPill(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    player: PlayerState,
    morph: PlayerMorph,
    controlsActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val reduced = rememberReducedMotion()
    val shape = RoundedCornerShape(50)
    val anchors = remember { BarAnchors() }
    val burst = remember { BarBurst() }
    val played = rememberPlayedFraction(player)
    val playInteraction = remember { MutableInteractionSource() }
    val playPressed by playInteraction.collectIsPressedAsState()
    val playScale = animateFloatAsState(if (playPressed && !reduced) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 1400f), label = "playPress")
    val track = c.primary.copy(alpha = 0.3f).compositeOver(c.surfaceVariant)
    val fillPath = remember { Path() }

    Box(modifier.widthIn(max = NavWidth).height(NavHeight).fillMaxWidth().onPlaced { anchors.outer = it }) {
        Surface(
            modifier = Modifier
                // Narrows to the compact player bar around its centre (layout only, per frame).
                .layout { measurable, constraints ->
                    val full = constraints.maxWidth
                    val compact = CompactBarWidth.roundToPx().coerceAtMost(full)
                    val t = FastOutSlowInEasing.transform(ramp(morph.progress.value, NarrowStart, NarrowEnd))
                    val w = lerpF(full.toFloat(), compact.toFloat(), t).roundToInt()
                    anchors.fullWidth = full
                    val bar = measurable.measure(Constraints.fixed(w, constraints.maxHeight))
                    layout(full, constraints.maxHeight) { bar.place((full - w) / 2, 0) }
                }
                .shadow(20.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.12f)),
            shape = shape,
            color = c.surfaceVariant,
            tonalElevation = 10.dp,
            shadowElevation = 0.dp,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onPlaced { anchors.bar = it }
                    // The moving pill: the selected tab's pill on its way to becoming Play
                    // (at rest in tabs the tab draws its own, so tab switches keep their fade).
                    .drawBehind {
                        val p = morph.progress.value
                        if (p <= 0f) return@drawBehind
                        val rect = anchors.pillRect(selected, p, playScale.value) ?: return@drawBehind
                        val trackT = ramp(p, TrackFadeStart, TrackFadeEnd)
                        val corner = CornerRadius(rect.height / 2f)
                        drawRoundRect(lerp(c.primary, track, trackT), rect.topLeft, rect.size, corner)
                        if (trackT > 0f && played.value > 0f) {
                            fillPath.reset()
                            fillPath.addRoundRect(RoundRect(rect, corner))
                            clipPath(fillPath) {
                                drawRect(c.primary, rect.topLeft, Size(rect.width * played.value, rect.height))
                            }
                        }
                    },
            ) {
                // ── Tabs ── (kept at the full width, centred, while the bar narrows under them)
                Row(
                    Modifier
                        .fillMaxSize()
                        .layout { measurable, constraints ->
                            val full = anchors.fullWidth.coerceAtLeast(constraints.maxWidth)
                            val tabs = measurable.measure(Constraints.fixed(full, constraints.maxHeight))
                            layout(constraints.maxWidth, constraints.maxHeight) { tabs.place((constraints.maxWidth - full) / 2, 0) }
                        }
                        .padding(BarPadding),
                    horizontalArrangement = Arrangement.spacedBy(BarGap),
                ) {
                    Tab.entries.forEachIndexed { i, t ->
                        val active = t == selected
                        val spec = if (reduced) snap<Float>() else tween(260, easing = Motion.Emphasized)
                        val labelColor by animateColorAsState(
                            if (active) c.onPrimary else c.onSurfaceVariant,
                            if (reduced) snap() else tween(220, easing = Motion.Emphasized), label = "tabContent",
                        )
                        // Fade the pill by alpha of the same color (never toward Color.Transparent).
                        val pillColor by animateColorAsState(
                            // Full primary (not the pale container tone) so the selected tab stands
                            // out clearly against the bar in light and dark (Derek, 2026-10-05).
                            if (active) c.primary else c.primary.copy(alpha = 0f),
                            if (reduced) snap() else tween(220, easing = Motion.Emphasized), label = "tabPill",
                        )
                        val weight by animateFloatAsState(if (active) SELECTED_TAB_WEIGHT else 1f, spec, label = "tabWeight")
                        val interaction = remember(t) { MutableInteractionSource() }
                        Box(
                            Modifier
                                .weight(weight.coerceAtLeast(0.75f))
                                .height(50.dp)
                                .onPlaced { anchors.tabs[t.ordinal] = it }
                                .graphicsLayer {
                                    val k = ramp(morph.progress.value, i * TabExitStagger, TabExitSpan + i * TabExitStagger)
                                    alpha = 1f - k
                                    translationY = 14.dp.toPx() * k
                                    scaleX = 1f - 0.4f * k
                                    scaleY = scaleX
                                }
                                // While the player owns the bar there's no click handler at all,
                                // so touches reach the player buttons above.
                                .then(
                                    if (controlsActive) Modifier else Modifier.clickable(interactionSource = interaction, indication = null) {
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        onSelect(t)
                                    },
                                )
                                .drawBehind {
                                    if (morph.progress.value <= 0f) drawRoundRect(pillColor, cornerRadius = CornerRadius(size.height / 2f))
                                },
                        ) {
                            CompositionLocalProvider(LocalContentColor provides labelColor) {
                                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (active) t.selectedIcon else t.icon, contentDescription = t.label, modifier = Modifier.size(22.dp))
                                    AnimatedVisibility(
                                        visible = active,
                                        enter = if (reduced) fadeIn(snap()) else fadeIn(tween(160, easing = Motion.Emphasized)) + expandHorizontally(tween(200, easing = Motion.Emphasized)),
                                        exit = if (reduced) fadeOut(snap()) else fadeOut(tween(90)) + shrinkHorizontally(tween(160, easing = Motion.Emphasized)),
                                    ) {
                                        Text(t.label, modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── The player's controls ──
                Row(
                    Modifier.align(Alignment.Center).width(CompactBarWidth).fillMaxHeight().padding(BarPadding),
                    horizontalArrangement = Arrangement.spacedBy(BarGap),
                ) {
                    SeekButton(-1, player, morph, anchors, burst, controlsActive, Modifier.width(SeekSlotWidth))
                    PlaySlot(player, morph, anchors, played, playInteraction, playScale, track, controlsActive, Modifier.width(PlaySlotWidth))
                    SeekButton(1, player, morph, anchors, burst, controlsActive, Modifier.width(SeekSlotWidth))
                }
            }
        }
        BurstBubble(burst, anchors)
    }
}

/** Where the bar's parts sit, in the bar's own pixels (layout positions, before any layer transforms). */
private class BarAnchors {
    /** The full-width slot the bar sits in (the burst bubble is placed in it). */
    var outer: LayoutCoordinates? = null
    var bar: LayoutCoordinates? = null
    /** The tab bar's width, which the tabs keep while the bar narrows. */
    var fullWidth = 0
    val tabs = arrayOfNulls<LayoutCoordinates>(Tab.entries.size)
    /** −10, play, +10. */
    val controls = arrayOfNulls<LayoutCoordinates>(3)
    var playIcon: LayoutCoordinates? = null

    fun rect(child: LayoutCoordinates?, within: LayoutCoordinates? = bar): Rect? {
        val b = within ?: return null
        val c = child ?: return null
        if (!b.isAttached || !c.isAttached) return null
        return b.localBoundingBoxOf(c, clipBounds = false)
    }

    /** The moving pill at morph progress [p]: selected tab → play slot, stretched mid-way. */
    fun pillRect(selected: Tab, p: Float, pressScale: Float): Rect? {
        val from = rect(tabs[selected.ordinal]) ?: return null
        val to = rect(controls[1]) ?: return null
        val t = FastOutSlowInEasing.transform(ramp(p, PillTravelStart, PillTravelEnd))
        val r = lerpRect(from, to, t)
        val stretch = sin(PI * t).toFloat()
        val w = r.width * (1f + 0.14f * stretch) * pressScale
        val h = r.height * (1f - 0.08f * stretch) * pressScale
        return Rect(Offset(r.center.x - w / 2f, r.center.y - h / 2f), Size(w, h))
    }
}

/**
 * How much of the video has played, 0..1, for the play pill's fill. The position ticks every
 * 250 ms, so small steps snap; a jump (±10, a seek, a new video) springs to the new spot.
 */
@Composable
private fun rememberPlayedFraction(player: PlayerState): Animatable<Float, *> {
    val fraction = remember { Animatable(0f) }
    val reduced = rememberReducedMotion()
    LaunchedEffect(player) {
        snapshotFlow {
            if (player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
        }.collect { target ->
            if (reduced || abs(target - fraction.value) < 0.01f) fraction.snapTo(target)
            else launch { fraction.animateTo(target, spring(dampingRatio = 0.8f, stiffness = 380f)) }
        }
    }
    return fraction
}

/** Springs a bar control up from below as the player opens (and back down as it closes). */
private fun Modifier.barEnter(morph: PlayerMorph, start: Float) = graphicsLayer {
    val p = morph.progress.value
    val e = Motion.Pop.transform(ramp(p, start, start + ControlEnterSpan))
    alpha = ramp(p, start, start + 0.18f)
    translationY = 16.dp.toPx() * (1f - e)
    scaleX = 0.6f + 0.4f * e
    scaleY = scaleX
}

@Composable
private fun PlaySlot(
    player: PlayerState,
    morph: PlayerMorph,
    anchors: BarAnchors,
    played: Animatable<Float, *>,
    interaction: MutableInteractionSource,
    pressScale: androidx.compose.runtime.State<Float>,
    track: Color,
    enabled: Boolean,
    modifier: Modifier,
) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val playing = player.isPlaying
    val painter = rememberVectorPainter(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow)
    val label = if (playing) "Pause" else "Play"
    Box(
        modifier
            .fillMaxHeight()
            .onPlaced { anchors.controls[1] = it }
            .then(
                if (!enabled) Modifier else Modifier.clickable(interactionSource = interaction, indication = null) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    player.togglePlay()
                },
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .onPlaced { anchors.playIcon = it }
                .barEnter(morph, PlayEnterStart)
                .graphicsLayer { scaleX *= pressScale.value; scaleY *= pressScale.value }
                .squishOn(playing)
                // Two-toned across the fill's edge: on-primary over the fill, the page's text
                // color over the pale track (on-primary while the whole pill is still primary).
                .drawBehind {
                    val p = morph.progress.value
                    val outside = lerp(c.onPrimary, c.onSurface, ramp(p, TrackFadeStart, TrackFadeEnd))
                    with(painter) { draw(size, colorFilter = ColorFilter.tint(outside)) }
                    val pill = anchors.pillRect(Tab.Home, 1f, 1f) ?: return@drawBehind
                    val icon = anchors.rect(anchors.playIcon) ?: return@drawBehind
                    val edge = pill.left + pill.width * played.value - icon.left
                    if (edge > 0f) clipRect(right = edge) { with(painter) { draw(size, colorFilter = ColorFilter.tint(c.onPrimary)) } }
                },
        )
    }
}

@Composable
private fun SeekButton(
    side: Int,
    player: PlayerState,
    morph: PlayerMorph,
    anchors: BarAnchors,
    burst: BarBurst,
    enabled: Boolean,
    modifier: Modifier,
) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val spin = remember { Animatable(0f) }
    Box(
        modifier
            .fillMaxHeight()
            .onPlaced { anchors.controls[if (side < 0) 0 else 2] = it }
            .barEnter(morph, SeekEnterStart)
            .pressable(enabled = enabled) {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                burst.tap(side, player.seekBy(side * 10_000L))
                // The arrow kicks the way it seeks.
                if (!reduced) scope.launch { spin.animateTo(0f, keyframes { durationMillis = 420; side * 50f at 150 }) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (side < 0) Icons.Rounded.Replay10 else Icons.Rounded.Forward10,
            contentDescription = if (side < 0) "Back 10 seconds" else "Forward 10 seconds",
            tint = c.onSurface,
            modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = spin.value },
        )
    }
}

/** Running total of a burst of ±10 taps on the bar (the same language as double-tap seek). */
@Stable
private class BarBurst {
    var side by mutableIntStateOf(0)
    var seconds by mutableIntStateOf(0)
    var taps by mutableIntStateOf(0)
    var active by mutableStateOf(false)

    fun tap(tappedSide: Int, movedMs: Long) {
        if (!active || side != tappedSide) { side = tappedSide; seconds = 0 }
        seconds += ((abs(movedMs) + 500) / 1000).toInt()
        taps++
        active = true
    }
}

/**
 * The burst's total in a small tonal pill just above the button that was tapped: springs in,
 * squishes on every tap, fades once the burst is over.
 */
@Composable
private fun BurstBubble(burst: BarBurst, anchors: BarAnchors) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    LaunchedEffect(burst.taps) {
        if (burst.taps == 0) return@LaunchedEffect
        delay(BarBurstWindowMs)
        burst.active = false
    }
    if (burst.side == 0) return
    val alpha by animateFloatAsState(
        if (burst.active) 1f else 0f,
        tween(if (reduced) 0 else if (burst.active) 140 else 220, easing = Motion.Emphasized), label = "burstAlpha",
    )
    val scale by animateFloatAsState(
        if (burst.active || reduced) 1f else 0.85f,
        if (burst.active) spring(dampingRatio = 0.5f, stiffness = 500f) else tween(220, easing = Motion.Emphasized), label = "burstScale",
    )
    val squish = remember { Animatable(1f) }
    LaunchedEffect(burst.taps) {
        if (burst.taps <= 1 || reduced) return@LaunchedEffect
        squish.animateTo(1f, keyframes { durationMillis = 420; 1.12f at 120; 0.97f at 270 })
    }
    val side = burst.side
    Row(
        Modifier
            .layout { measurable, _ ->
                // Centred over its button, 10 dp above the bar (drawn outside the bar's bounds).
                val bubble = measurable.measure(Constraints())
                layout(0, 0) {
                    val button = anchors.rect(anchors.controls[if (side < 0) 0 else 2], within = anchors.outer)
                    val x = (button?.center?.x ?: 0f) - bubble.width / 2f
                    bubble.place(x.roundToInt(), -bubble.height - 10.dp.roundToPx())
                }
            }
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale * squish.value
                scaleY = scale * (2f - squish.value)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.18f))
            .background(c.surfaceContainerHighest, CircleShape)
            .height(38.dp)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (side < 0) "−" else "+", color = c.primary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
        Spacer(Modifier.width(2.dp))
        RollingText("${burst.seconds}", c.onSurface, up = true, fontSize = 16.sp)
    }
}
