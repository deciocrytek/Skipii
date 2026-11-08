package com.decio.skipii

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.foundation.focusGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A screen header that gets out of the way: it slides up exactly as far as the list scrolls
 * down, comes straight back on any upward scroll, and on release settles fully shown or
 * fully hidden. The page runs to the top edge; as the header leaves, a soft top vignette
 * (the bottom one's [vignetteBrush], mirrored, half its depth) fades in behind the status bar.
 *
 * [content] gets the header's height to use as its top content padding. [resetKey] changing
 * (refresh, scroll-to-top, new search) brings the header back. [pinned] is drawn over the
 * content and under the header, told where the header's bottom edge is right now (px, 0 once
 * it's gone): a row that sticks under it (a channel's tabs).
 */
@Composable
fun CollapsingHeaderLayout(
    resetKey: Any? = null,
    header: @Composable () -> Unit,
    pinned: (@Composable (headerBottom: () -> Float) -> Unit)? = null,
    content: @Composable (top: Dp) -> Unit,
) {
    val density = LocalDensity.current
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val scope = rememberCoroutineScope()
    var headerPx by remember { mutableIntStateOf(0) }
    /** 0 = fully shown, -headerPx = fully hidden. */
    val offset = remember { Animatable(0f) }
    val settleSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 420f)

    LaunchedEffect(resetKey) { if (reduced) offset.snapTo(0f) else offset.animateTo(0f, settleSpec) }

    val connection = remember(reduced) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val h = headerPx.toFloat()
                if (h <= 0f || consumed.y == 0f) return Offset.Zero
                // Follows what the list actually scrolled, so a list too short to scroll
                // (or the pull-to-refresh overscroll) never moves the header.
                val target = (offset.value + consumed.y).coerceIn(-h, 0f)
                scope.launch { offset.snapTo(target) }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val h = headerPx.toFloat()
                val v = offset.value
                if (h > 0f && v < 0f && v > -h) {
                    val target = if (v < -h / 2f) -h else 0f
                    if (reduced) offset.snapTo(target) else offset.animateTo(target, settleSpec)
                }
                return Velocity.Zero
            }
        }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // A TV remote: the list runs under the header, so to the D-pad nothing in it lies "below" the header's buttons
    // (the nearest thing down was on the page behind). ▼ off the header goes into the list.
    val tv = LocalWindowLayout.current.tv
    val body = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().nestedScroll(connection)) {
        Box(if (tv) Modifier.fillMaxSize().focusRequester(body).focusGroup() else Modifier.fillMaxSize()) {
            content(with(density) { headerPx.toDp() })
        }
        // Top vignette, shown in proportion to how hidden the header is.
        val page = c.surface
        val vignette = remember(page) { vignetteBrush(page, opaqueAtTop = true) }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height((statusTop + 72.dp) / 2)
                .graphicsLayer { alpha = if (headerPx > 0) (-offset.value / headerPx).coerceIn(0f, 1f) else 0f }
                .drawBehind { drawRect(vignette) },
        )
        pinned?.invoke { (headerPx + offset.value).coerceAtLeast(0f) }
        Box(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerPx = it.height }
                .graphicsLayer {
                    translationY = offset.value
                    // Eases out as it leaves, so the edge never reads as a hard sliding slab.
                    alpha = if (headerPx > 0) 1f - 0.5f * (-offset.value / headerPx).coerceIn(0f, 1f) else 1f
                }
                .background(c.surface)
                .then(if (tv) Modifier.leaveTo(body, listOf(FocusDirection.Down)) else Modifier),
        ) { header() }
    }
}

/** Strongest a page vignette gets (Derek: 80% of Ledgii's 95%). */
const val VignetteAlpha = 0.76f

/**
 * Page color fading from [VignetteAlpha] to 0 on a smoothstep ramp: flat at both ends, so
 * there's no visible line where the vignette begins. [opaqueAtTop] puts the strong end on top.
 */
fun vignetteBrush(color: Color, opaqueAtTop: Boolean): Brush {
    val stops = 12
    return Brush.verticalGradient(
        *Array(stops + 1) { i ->
            val t = i / stops.toFloat()
            val strength = if (opaqueAtTop) 1f - t else t
            t to color.copy(alpha = VignetteAlpha * strength * strength * (3f - 2f * strength))
        },
    )
}

/** Bottom padding as given, plus the collapsing header's height on top. */
fun PaddingValues.withTop(top: Dp) = PaddingValues(top = top, bottom = calculateBottomPadding())
