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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
// 0.5.50: five tabs (Shorts); the active pill keeps about the width it had among four, so
// "Subscriptions" still fits.
private const val SELECTED_TAB_WEIGHT = 3.5f
// The open player's bar is compact (Derek, 2026-10-19: play pill ~60% of its first 161 dp,
// the ±10 buttons tight beside it): it narrows from the tab bar's width as the player opens.
// 0.5.32 (Derek, 2026-10-25: mockup A "Lift" in docs/design/player-bar-reach.html): a size up
// and lifted off the tab bar's spot, so one thumb reaches it — 264×72, play 120×60, ±10 60,
// its bottom 36 dp above the tab bar's.
// 0.5.34: audio only on the left and the ⋮ key on the right (PlayerBar.kt), play dead centre.
// 0.5.36 (Derek): 15% smaller at rest so opening it reads as a bigger change — 316×61, play
// 102×51, ±10 51, side buttons 41 (the card, panels and scrub track keep their sizes).
internal val PlaySlotWidth = 102.dp
internal val SeekSlotWidth = 51.dp
internal val BarPadding = 6.dp
/** The open player's own inset (the tabs keep [BarPadding]). */
internal val PlayerBarPadding = 5.dp
private val BarGap = 4.dp
internal val PlayerBarGap = 5.dp
internal val CompactBarWidth = PlayerBarPadding * 2 + SideSlotWidth * 2 + SeekSlotWidth * 2 + PlaySlotWidth + PlayerBarGap * 4
// 0.5.37 (Derek): 10% taller (row 67) and, under it, the progress hairline's 8 dp (mockup A "Inside" in
// docs/design/player-bar-progress.html): 316×75 at rest.
// 0.5.38 (Derek): as much room above the buttons as below them (their inset + the hairline's
// room): 8 dp on top, 316×83.
internal val PlayerRowHeight = 67.dp
internal val PlayerProgressRoom = 8.dp
internal val PlayerTopRoom = PlayerProgressRoom
internal val PlayerBarHeight = PlayerTopRoom + PlayerRowHeight + PlayerProgressRoom
internal val PlayerBarLift = 36.dp
/** How far the open player's bar reaches above the tab bar's top edge. */
internal val PlayerBarRaise = PlayerBarLift + PlayerBarHeight - NavHeight
private val PlayerIconSize = 26.dp

// Where each part of the bar's morph happens along PlayerMorph.progress (tuning dials).
private const val TabExitStagger = 0.04f
private const val TabExitSpan = 0.25f
private const val PillTravelStart = 0.06f
private const val PillTravelEnd = 0.7f
private const val PlayEnterStart = 0.28f
private const val SeekEnterStart = 0.34f
private const val KeyEnterStart = 0.4f
private const val ControlEnterSpan = 0.45f
private const val NarrowStart = 0.1f
private const val NarrowEnd = 0.75f
private const val GrowStart = 0.2f
private const val GrowEnd = 0.85f
// The lift lags the narrowing a little and lands with a small overshoot (the bar steps up).
private const val LiftStart = 0.22f
private const val LiftEnd = 0.92f
private const val LiftOvershoot = 1.1f
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
 *
 * 0.5.34: the bar holds every playback control (PlayerBar.kt): a ⋮ key, the tools card, the
 * quality list and the scrub track. Its size and outline follow [bar]'s values per frame on
 * top of the morph, so the capsule is one shape that grows into each of them.
 *
 * 0.5.50: on the Shorts tab the tabs drop out and the bar's tone fades along [shorts] (0..1)
 * while the glass bar (Shorts.kt) splits out of the same spot; [tabsEnabled] is off meanwhile.
 */
@Composable
internal fun NavPill(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    player: PlayerState,
    morph: PlayerMorph,
    controlsActive: Boolean,
    bar: PlayerBarState,
    onFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    shorts: () -> Float = { 0f },
    tabsEnabled: Boolean = true,
) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    val reduced = rememberReducedMotion()
    SideEffect { bar.reduced = reduced }
    val density = LocalDensity.current
    // The card may use the whole width between the screen's 16 dp margins.
    val maxBarWidth = with(density) { (LocalConfiguration.current.screenWidthDp.dp - 32.dp).toPx() }
    val pip = LocalPictureInPicture.current
    val scope = rememberCoroutineScope()
    val gate = { barGate(morph.progress.value) }
    val onPick: (BarTool) -> Unit = { tool -> pickTool(tool, bar, player, pip, onFullscreen) }
    val tools = barTools(player, pip)
    val hasChapters = !player.details?.chapters.isNullOrEmpty()
    SideEffect { bar.tools = tools; bar.hasChapters = hasChapters }
    val sheets = rememberStoryboardSheets(player)
    // The player's row is wider than the tab bar; on a narrower window it shrinks to fit.
    val rowScale = with(density) { (maxBarWidth / CompactBarWidth.toPx()).coerceAtMost(1f) }
    val anchors = remember { BarAnchors() }
    val burst = remember { BarBurst() }
    val played = rememberPlayedFraction(player)
    val playInteraction = remember { MutableInteractionSource() }
    val playPressed by playInteraction.collectIsPressedAsState()
    val playScale = animateFloatAsState(if (playPressed && !reduced) 0.94f else 1f, spring(dampingRatio = 0.6f, stiffness = 1400f), label = "playPress")

    Box(modifier.widthIn(max = NavWidth).height(NavHeight).fillMaxWidth().onPlaced { anchors.outer = it }) {
        Box(
            modifier = Modifier
                // Narrows to the compact player bar around its centre and grows upward to the
                // player bar's height (layout only, per frame); the slot itself keeps the tab
                // bar's size, so nothing around it moves. Open, it grows on into the tools card, the
                // quality list or the scrub track (PlayerBar.kt), still bottom-centred.
                .layout { measurable, constraints ->
                    val full = constraints.maxWidth
                    val compact = CompactBarWidth.toPx().coerceAtMost(maxBarWidth)
                    val p = morph.progress.value
                    val t = FastOutSlowInEasing.transform(ramp(p, NarrowStart, NarrowEnd))
                    val restW = lerpF(full.toFloat(), compact, t)
                    val g = FastOutSlowInEasing.transform(ramp(p, GrowStart, GrowEnd))
                    val restH = lerpF(constraints.maxHeight.toFloat(), PlayerBarHeight.toPx(), g)
                    val size = playerBarSize(bar, player, restW, restH, maxBarWidth, barGate(p))
                    val w = size.width.roundToInt()
                    val h = size.height.roundToInt()
                    anchors.fullWidth = full
                    val placeable = measurable.measure(Constraints.fixed(w, h))
                    // The open card also reaches a little below the resting bar (its time row).
                    val below = playerBarBelow(bar, barGate(p)).roundToInt()
                    layout(full, constraints.maxHeight) { placeable.place((full - w) / 2, constraints.maxHeight - h + below) }
                }
                // Lifts off the tab bar's spot toward the thumb (a layer move, so touches follow
                // it); the outline is redrawn every frame, so the shadow and clip follow the shape.
                // A real, if soft, shadow in every state (Derek: the bar was white on white): full
                // black here, as Android already scales elevation shadows right down.
                .graphicsLayer {
                    val p = morph.progress.value
                    // Scrubbing lifts it further, clear of the thumb, on a deeper shadow (PlayerBar.kt).
                    translationY = -PlayerBarLift.toPx() * backOut(ramp(p, LiftStart, LiftEnd), LiftOvershoot) -
                        playerBarScrubLift(bar, barGate(p))
                    shape = playerBarShape(bar, size, barGate(p))
                    clip = true
                    shadowElevation = playerBarElevation(bar, barGate(p)) * (1f - shortsBarFade(shorts()))
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                }
                .drawBehind { drawRect(c.surfaceVariant, alpha = 1f - shortsBarFade(shorts())) }
                .playerBarGestures(bar, player, enabled = controlsActive, view = view, gate = gate, onPick = onPick),
        ) {
          CompositionLocalProvider(LocalContentColor provides c.onSurfaceVariant) {
            Box(
                Modifier
                    // Everything inside keeps to the resting bar's bottom; only the card's time row
                    // (BarCard) sits in the strip it grows below that.
                    .layout { measurable, constraints ->
                        val below = playerBarBelow(bar, gate()).roundToInt()
                        val h = (constraints.maxHeight - below).coerceAtLeast(0)
                        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, h))
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                    }
                    .onPlaced { anchors.bar = it }
                    // The moving pill: the selected tab's pill on its way to becoming Play
                    // (at rest in tabs the tab draws its own, so tab switches keep their fade).
                    .drawBehind {
                        val p = morph.progress.value
                        if (p <= 0f) return@drawBehind
                        val rect = anchors.pillRect(selected, p, playScale.value) ?: return@drawBehind
                        // Scrubbing: the chapter track grows out of play (PlayerBar.kt), so play fades
                        // under it rather than showing around its thinner edges.
                        val shown = 1f - ramp(bar.scrub.value, 0f, 0.3f)
                        if (shown <= 0f) return@drawBehind
                        // A plain filled button: the progress hairline under the row shows how far in (0.5.37).
                        drawRoundRect(c.primary, rect.topLeft, rect.size, CornerRadius(rect.height / 2f), alpha = shown)
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
                    verticalAlignment = Alignment.CenterVertically,
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
                                    // Into Shorts: Home fades where ‹ appears, the rest drop out left to right.
                                    val sp = shorts()
                                    val d = shortsTabDrop(sp, i)
                                    alpha = (1f - k) * (if (i == 0) 1f - shortsHomeFade(sp) else 1f - d)
                                    translationY = 14.dp.toPx() * k + 16.dp.toPx() * d
                                    scaleX = (1f - 0.4f * k) * (1f - 0.15f * d)
                                    scaleY = scaleX
                                }
                                // While the player owns the bar there's no click handler at all,
                                // so touches reach the player buttons above.
                                .then(
                                    if (controlsActive || !tabsEnabled) Modifier else Modifier.clickable(interactionSource = interaction, indication = null) {
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

                // ── The player's controls ── (bottom-centred: the bar grows upward from them)
                val scrubFade = Modifier.graphicsLayer { alpha = 1f - ramp(bar.scrub.value, 0f, 0.45f) }
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        // The progress hairline runs underneath (BarProgress).
                        .padding(bottom = PlayerProgressRoom)
                        .requiredWidth(CompactBarWidth)
                        .heightIn(max = PlayerRowHeight)
                        .fillMaxHeight()
                        .graphicsLayer { scaleX = rowScale; scaleY = rowScale; transformOrigin = TransformOrigin(0.5f, 1f) }
                        .padding(PlayerBarPadding),
                    horizontalArrangement = Arrangement.spacedBy(PlayerBarGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarAudioOnly(player, enabled = controlsActive, modifier = Modifier.barEnter(morph, KeyEnterStart).then(scrubFade))
                    SeekButton(-1, player, morph, anchors, burst, controlsActive, Modifier.width(SeekSlotWidth).then(scrubFade))
                    PlaySlot(player, morph, anchors, playInteraction, playScale, controlsActive, Modifier.width(PlaySlotWidth))
                    SeekButton(1, player, morph, anchors, burst, controlsActive, Modifier.width(SeekSlotWidth).then(scrubFade))
                    BarKey(bar, enabled = controlsActive, modifier = Modifier.barEnter(morph, KeyEnterStart).then(scrubFade))
                }
                BarProgress(bar, player, played, gate)
                if (bar.cardShown) BarCard(bar, player, gate, onPick)
                if (bar.panelShown) BarPanelContent(bar, player, gate, scope, sheets)
            }
          }
        }
        BurstBubble(burst, anchors)
        // The landing frame and Cancel, over the scrub track (outside the bar's clip).
        ScrubOverlay(bar, player, sheets, gate) { anchors.rect(anchors.bar, within = anchors.outer) }
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
    interaction: MutableInteractionSource,
    pressScale: androidx.compose.runtime.State<Float>,
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
                .size(PlayerIconSize)
                .onPlaced { anchors.playIcon = it }
                .barEnter(morph, PlayEnterStart)
                .graphicsLayer { scaleX *= pressScale.value; scaleY *= pressScale.value }
                .squishOn(playing)
                .drawBehind { with(painter) { draw(size, colorFilter = ColorFilter.tint(c.onPrimary)) } },
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
            modifier = Modifier.size(PlayerIconSize).graphicsLayer { rotationZ = spin.value },
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
                // Centred over its button, 10 dp above the lifted bar (drawn outside the slot's bounds).
                val bubble = measurable.measure(Constraints())
                layout(0, 0) {
                    val button = anchors.rect(anchors.controls[if (side < 0) 0 else 2], within = anchors.outer)
                    val x = (button?.center?.x ?: 0f) - bubble.width / 2f
                    val top = (button?.top ?: 0f) - PlayerBarPadding.toPx()
                    bubble.place(x.roundToInt(), (top - bubble.height - 10.dp.toPx()).roundToInt())
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

/** Back-out easing: overshoots by [tension] and settles at 1 (the bar's lift landing). */
private fun backOut(t: Float, tension: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    val u = t - 1f
    return 1f + u * u * ((tension + 1f) * u + tension)
}

/** The bar's own extras (card, list, track) only ever open on the fully open player. */
private fun barGate(progress: Float) = ramp(progress, 0.85f, 1f)
