package com.decio.skipii

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.animation.core.tween
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The rail's own width; pages on a wide window start this far in (plus the inset beside it). */
internal val RailWidth = 84.dp

/**
 * Wide windows (Rail 3): the five tabs stand in a rail down the left instead of the floating pill.
 * Same tabs, same rules: the selected one in full primary, a re-tap scrolls its list to the top.
 */
@Composable
fun NavRail(selected: Tab, tabs: List<Tab>, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
            .width(RailWidth)
            .padding(top = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The page's own header carries the brand mark; the rail starts level with its title.
        Spacer(Modifier.height(10.dp))
        tabs.forEach { tab -> RailTab(tab, tab == selected) { onSelect(tab) } }
    }
}

@Composable
private fun RailTab(tab: Tab, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val on by animateFloatAsState(if (selected) 1f else 0f, if (reduced) snap() else spring(dampingRatio = 0.7f, stiffness = 700f), label = "railTab")
    val ink by animateColorAsState(if (selected) c.onPrimary else c.onSurfaceVariant, label = "railInk")
    // Reached with a remote (TV): the pill comes out in a tone of the ink, under the picked one's.
    var focused by remember { mutableStateOf(false) }
    val focus by animateFloatAsState(if (focused) 1f else 0f, if (reduced) snap() else tween(140), label = "railFocus")
    Column(
        Modifier.width(RailWidth).onFocusChanged { focused = it.isFocused }.pressable(focusTone = false, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 58.dp, height = 34.dp), contentAlignment = Alignment.Center) {
            // A size up from the pill, so on the picked tab it still shows, as a halo.
            Box(
                Modifier
                    .requiredSize(width = 72.dp, height = 46.dp)
                    .graphicsLayer { alpha = focus; scaleX = 0.6f + 0.4f * focus; scaleY = 0.85f + 0.15f * focus }
                    .clip(RoundedCornerShape(23.dp))
                    .background(c.onSurface.copy(alpha = 0.2f)),
            )
            // The pill grows out from the icon as the tab is picked (alpha and scale, never a colour to transparent).
            Box(
                Modifier
                    .size(width = 58.dp, height = 34.dp)
                    .graphicsLayer { alpha = on; scaleX = 0.6f + 0.4f * on; scaleY = 0.85f + 0.15f * on }
                    .clip(RoundedCornerShape(17.dp))
                    .background(c.primary),
            )
            Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            tab.label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
            color = if (selected) c.onSurface else c.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
