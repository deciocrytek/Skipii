package com.decio.skipii

import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── Shapes ─────────────────────────────────────────────────────────────────────

/**
 * Material 3 Expressive "cookie"/"clover" family: a circle whose radius ripples with
 * [lobes] bumps of relative depth [depth]. Used for channel avatars and tile icons.
 */
class LobedShape(private val lobes: Int, private val depth: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val steps = 120
        for (i in 0..steps) {
            val t = (i.toFloat() / steps) * 2f * PI.toFloat()
            val r = 1f - depth + depth * cos(lobes * t)
            val x = cx + cx * r * cos(t)
            val y = cy + cy * r * sin(t)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

val CookieShape = LobedShape(lobes = 9, depth = 0.075f)
val SunnyShape = LobedShape(lobes = 8, depth = 0.05f)

// ── Header ─────────────────────────────────────────────────────────────────────

/** Seamless header: same surface as the page, heavy title, round 44 dp actions. */
@Composable
fun ScreenHeader(
    title: String,
    brandMark: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (brandMark) {
            BrandMark()
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            fontFamily = RoundedHeavy,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 31.sp,
            letterSpacing = (-1.3).sp,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

@Composable
fun BrandMark() {
    val c = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 30.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Brush.linearGradient(listOf(c.primary, c.primary.copy(alpha = 0.82f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.FastForward, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun CircleAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(44.dp)
            .pressable(onClick = onClick)
            .clip(CircleShape)
            .background(c.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = c.onSurface, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
        fontFamily = RoundedHeavy,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 21.sp,
        letterSpacing = (-0.6).sp,
    )
}

/**
 * An invisible strip over the system's home-gesture zone at the very bottom (Derek,
 * 2026-10-12). The page still draws through it, but a touch that starts there never reaches
 * the list beneath: the system only claims the swipe after a few moves, so without this every
 * swipe home first nudged the page up. Place it above the scrolling content, below anything
 * tappable that reaches into the zone.
 */
@Composable
fun BottomGestureGuard(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.mandatorySystemGestures)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    )
}

// ── Video cards ────────────────────────────────────────────────────────────────

/**
 * Starts extracting a video's streams the moment a finger rests on it, ~100+ ms before the
 * tap completes. A touch that turns into a scroll (moves past touch slop within 60 ms)
 * doesn't prefetch. Observes only; never consumes, so clicks and scrolling are unaffected.
 */
fun Modifier.prefetchOnPress(videoId: String): Modifier = pointerInput(videoId) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val slop = viewConfiguration.touchSlop
        val scrolled = withTimeoutOrNull(60) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!change.pressed) return@withTimeoutOrNull false
                if ((change.position - down.position).getDistance() > slop) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        if (scrolled != true) StreamCache.prefetch(videoId)
    }
}

/** Long-pressing any video opens its options (Play next, Add to queue, …). Provided by the app shell. */
val LocalVideoMenu = staticCompositionLocalOf<(VideoItem) -> Unit> { {} }

@Composable
fun Thumbnail(url: String?, modifier: Modifier = Modifier, radius: Int = 22) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(radius.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@Composable
fun DurationPill(seconds: Long, modifier: Modifier = Modifier, small: Boolean = false) {
    if (seconds <= 0) return
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = if (small) 7.dp else 9.dp, vertical = if (small) 2.dp else 4.dp),
    ) {
        Text(formatDuration(seconds * 1000), color = Color.White, fontSize = if (small) 11.sp else 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A channel's (or the account's) picture: always a circle, as on YouTube (Derek, 2026-10-10).
 * Only someone with no picture at all gets the cookie-shaped initial.
 */
@Composable
fun ChannelAvatar(url: String?, name: String, size: Int = 40) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(size.dp).clip(if (url != null) CircleShape else CookieShape).background(c.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().trimStart('@').take(1).uppercase(),
            color = c.onTertiaryContainer,
            fontWeight = FontWeight.Black,
            fontSize = (size * 0.4f).sp,
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp))
        }
    }
}

/** Full-width feed card: rounded thumbnail inside a 28 dp card, then avatar + title + meta. */
@Composable
fun VideoCard(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val menu = LocalVideoMenu.current
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .fillMaxWidth()
            .prefetchOnPress(video.id)
            .pressable(pressedScale = 0.97f, onLongClick = { menu(video) }, onClick = onClick)
            .clip(shape)
            .background(c.surfaceContainerLow)
            .padding(6.dp),
    ) {
        Box {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(10.dp))
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Row(modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 12.dp, bottom = 10.dp)) {
            val openChannel = LocalChannelOpener.current
            Box(Modifier.pressable(enabled = video.channelUrl != null) { video.channelUrl?.let(openChannel) }) {
                ChannelAvatar(video.avatar, video.channel)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    metaLine(video.channel, video.views, video.uploaded),
                    color = c.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Compact row used for "More videos" under the player. */
@Composable
fun VideoRow(video: VideoItem, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val menu = LocalVideoMenu.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .prefetchOnPress(video.id)
            .pressable(pressedScale = 0.97f, onLongClick = { menu(video) }, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(140.dp)) {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 16)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(6.dp), small = true)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(video.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                listOfNotNull(video.channel.ifBlank { null }, video.views.takeIf { it >= 0 }?.let { "${formatCount(it)} views" }).joinDots(),
                color = c.onSurfaceVariant,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

/** How much of a video was watched, as a thin primary bar along the thumbnail's bottom. */
@Composable
fun ResumeBar(videoId: String, modifier: Modifier = Modifier) {
    val progress = ResumeStore.progress(videoId) ?: return
    val c = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.45f))) {
        Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(CircleShape).background(c.primary))
    }
}

// ── Empty / error states ───────────────────────────────────────────────────────

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(SunnyShape).background(c.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = c.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, icon: ImageVector? = null) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .pressable(onClick = onClick)
            .height(44.dp)
            .clip(CircleShape)
            .background(c.primary)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.onPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// ── Formatting ─────────────────────────────────────────────────────────────────

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    // Latin digits in every language, like the rest of the (English) UI: the default locale gave an
    // Arabic phone "٢٣:٥٧" beside "views", and "%.1f" a "٫" that removeSuffix(".0") never matched.
    return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
}

fun formatCount(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> trimmed(n / 1_000.0) + "K"
    n < 1_000_000_000 -> trimmed(n / 1_000_000.0) + "M"
    else -> trimmed(n / 1_000_000_000.0) + "B"
}

private fun trimmed(v: Double): String =
    if (v >= 100 || v == Math.floor(v)) v.toLong().toString() else "%.1f".format(Locale.US, v).removeSuffix(".0")

fun metaLine(channel: String, views: Long, uploaded: String?): String =
    listOfNotNull(
        channel.ifBlank { null },
        views.takeIf { it >= 0 }?.let { "${formatCount(it)} views" },
        uploaded?.ifBlank { null },
    ).joinDots()

/**
 * "Channel · 2.4M views · 5 years ago" with each part a bidi isolate (FSI … PDI), so one part's
 * direction never pulls in the next: an Arabic channel name took the following "2.4" into its own
 * right-to-left run and showed "2.4 قرآن كريم M views".
 */
fun List<String>.joinDots(): String = joinToString(" · ") { "\u2068$it\u2069" }

/** Android's share sheet for a link (a video, a short). */
fun shareLink(context: android.content.Context, url: String, title: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, url)
        .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
    context.startActivity(android.content.Intent.createChooser(send, null))
}
