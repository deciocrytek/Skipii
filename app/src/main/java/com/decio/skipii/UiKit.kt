package com.decio.skipii

import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
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

/**
 * Full-width feed card: rounded thumbnail inside a 28 dp card, then avatar + title + meta.
 * The thumbnail sits 11 dp from the screen's edge (6 + 5), half the old 22 (Derek, 2026-10-31: wider thumbnails).
 */
@Composable
fun VideoCard(video: VideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val menu = LocalVideoMenu.current
    val shape = RoundedCornerShape(28.dp)
    // On a remote (TV) the focused card lifts: a lighter container and a shadow under its scale-up.
    val tv = LocalWindowLayout.current.tv
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "cardLift")
    // The card that opened the video gets the remote back when the TV player closes (TvFocusMemory).
    val opener = remember { FocusRequester() }
    Column(
        modifier = modifier
            .padding(horizontal = 6.dp, vertical = 7.dp)
            .fillMaxWidth()
            .prefetchOnPress(video.id)
            .onFocusChanged { focused = it.isFocused }
            .then(if (tv) Modifier.focusRequester(opener) else Modifier)
            .pressable(pressedScale = 0.97f, focusedScale = 1.05f, focusTone = false, onLongClick = { menu(video) }) {
                if (tv) TvFocusMemory.opener = opener
                onClick()
            }
            .graphicsLayer { shadowElevation = 18.dp.toPx() * lift; this.shape = shape; clip = true; ambientShadowColor = Color.Black; spotShadowColor = Color.Black }
            .background(if (lift > 0f) androidx.compose.ui.graphics.lerp(c.surfaceContainerLow, c.surfaceContainerHighest, lift) else c.surfaceContainerLow)
            .padding(5.dp),
    ) {
        Box {
            Thumbnail(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = 23)
            DurationPill(video.durationSec, Modifier.align(Alignment.BottomEnd).padding(10.dp))
            ResumeBar(video.id, Modifier.align(Alignment.BottomStart))
        }
        Row(modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 12.dp, bottom = 10.dp)) {
            val openChannel = LocalChannelOpener.current
            // On a TV the card is one stop for the remote: the channel is in its long-press menu.
            Box(Modifier.pressable(enabled = video.channelUrl != null && !tv) { video.channelUrl?.let(openChannel) }) {
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
                CardMeta(video)
            }
        }
    }
}

/**
 * "Channel · 12K views · 46 minutes ago" on one line when it fits. When it doesn't (a narrow phone, a
 * big font), the channel gets its own line and views · age go underneath, so the age is never cut off.
 */
@Composable
private fun CardMeta(video: VideoItem) {
    val c = MaterialTheme.colorScheme
    val full = metaLine(video.channel, video.views, video.uploaded)
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val fits = remember(full, style, constraints.maxWidth) {
            measurer.measure(full, style, maxLines = 1, softWrap = false).size.width <= constraints.maxWidth
        }
        val rest = metaLine("", video.views, video.uploaded)
        val split = !fits && video.channel.isNotBlank() && rest.isNotEmpty()
        Column {
            (if (split) listOf(video.channel, rest) else listOf(full)).forEach {
                Text(
                    it,
                    color = c.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    // Two lines read as one block: the theme's default line height left a gap between them.
                    lineHeight = if (split) 18.sp else TextUnit.Unspecified,
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
                listOfNotNull(video.channel.ifBlank { null }, video.views.takeIf { it >= 0 }?.let { viewsLine(it) }).joinDots(),
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

/**
 * "1.2M" in the app's language ("1,2 M", "1,2 Mio.", "120万"), always in Latin digits: the default locale
 * gave an Arabic phone "١,٢M". The suffixes are strings (count_thousands …); Japanese counts in 万 / 億 (count_in_ten_thousands).
 */
fun formatCount(n: Long): String {
    if (n < 1_000) return n.toString()
    return if (AppLanguage.resources.getBoolean(R.bool.count_in_ten_thousands)) when {
        n < 10_000 -> n.toString()
        n < 100_000_000 -> str(R.string.count_ten_thousands, trimmed(n / 10_000.0))
        else -> str(R.string.count_hundred_millions, trimmed(n / 100_000_000.0))
    } else when {
        n < 1_000_000 -> str(R.string.count_thousands, trimmed(n / 1_000.0))
        n < 1_000_000_000 -> str(R.string.count_millions, trimmed(n / 1_000_000.0))
        else -> str(R.string.count_billions, trimmed(n / 1_000_000_000.0))
    }
}

private fun trimmed(v: Double): String =
    if (v >= 100 || v == Math.floor(v)) v.toLong().toString()
    else "%.1f".format(Locale.US, v).removeSuffix(".0").replace('.', decimalSeparator())

/** The app language's decimal mark with Latin digits ("1,2" in Spanish, "1.2" in Arabic as written with Latin digits). */
private fun decimalSeparator(): Char =
    android.icu.text.DecimalFormatSymbols.getInstance(latinDigits(AppLanguage.locale)).decimalSeparator

private fun latinDigits(locale: Locale): android.icu.util.ULocale =
    android.icu.util.ULocale.forLocale(locale).setKeywordValue("numbers", "latn")

/** "1.2M views" in the app's language. */
fun viewsLine(views: Long): String = plural(R.plurals.views_count, compactQuantity(views), formatCount(views))

/** "1.2M subscribers" in the app's language. */
fun subscribersLine(subscribers: Long): String = plural(R.plurals.subscribers_count, compactQuantity(subscribers), formatCount(subscribers))

/**
 * The plural form for a count shown by [formatCount]: the noun agrees with what's on screen ("1,2 M"), not
 * with the exact count. Russian takes "просмотров" after any "тыс." / "млн" (the exact 1,201 picked
 * "просмотр"); French, Spanish, Italian and Portuguese take "de" after a million ("1,2 M de vues"). So a
 * compacted count stands in as 1,000 or 1,000,000, which every language's rules read that way.
 */
fun compactQuantity(n: Long): Long = when {
    n < 1_000 -> n
    n < 1_000_000 -> 1_000
    else -> 1_000_000
}

fun metaLine(channel: String, views: Long, uploaded: String?): String =
    listOfNotNull(
        channel.ifBlank { null },
        views.takeIf { it >= 0 }?.let { viewsLine(it) },
        displayAge(uploaded)?.ifBlank { null },
    ).joinDots()

private val ENGLISH_AGE = Regex("^(?:(streamed(?: live)?|premiered)\\s+)?(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago$", RegexOption.IGNORE_CASE)

/**
 * An age as YouTube writes it in English ("3 days ago", "Streamed 2 hours ago", "just now" — Skipii asks for
 * English and keeps it so: NewVideos reads those ages) in the app's language ("hace 3 días"). Anything else
 * (a date) is shown as it came.
 */
fun displayAge(english: String?): String? {
    if (english == null) return null
    val text = english.trim()
    if (AppLanguage.locale.language == "en") return text
    if (text.equals("just now", ignoreCase = true)) return str(R.string.age_just_now)
    val m = ENGLISH_AGE.matchEntire(text) ?: return text
    val unit = when (m.groupValues[3].lowercase()) {
        "second" -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.SECONDS
        "minute" -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MINUTES
        "hour" -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.HOURS
        "day" -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.DAYS
        "week" -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.WEEKS
        "month" -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.MONTHS
        else -> android.icu.text.RelativeDateTimeFormatter.RelativeUnit.YEARS
    }
    val age = android.icu.text.RelativeDateTimeFormatter.getInstance(latinDigits(AppLanguage.locale))
        .format(m.groupValues[2].toDouble(), android.icu.text.RelativeDateTimeFormatter.Direction.LAST, unit)
    return when {
        m.groupValues[1].startsWith("streamed", ignoreCase = true) -> str(R.string.age_streamed, age)
        m.groupValues[1].isNotEmpty() -> str(R.string.age_premiered, age)
        else -> age
    }
}

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

// ── Seek glyphs ───────────────────────────────────────────────────────────────

/**
 * The −/+ glyph for a jump of [seconds] ([SeekStep], #36): Material's own for 5, 10 and 30. Material has
 * no 15, 20 or 25, so they're put together from its pieces: its arrows, its "1", "0" and "5" (moved into
 * place: Material's digits are all 2.89 of the 24 viewport wide, the shifts below are measured from its
 * paths), and a "2" drawn to match them ([twoAt]: same height, stroke and width).
 */
fun seekIcon(forward: Boolean, seconds: Int = SeekStep.seconds): ImageVector = when (seconds) {
    5 -> if (forward) Icons.Rounded.Forward5 else Icons.Rounded.Replay5
    15 -> if (forward) forward15 else replay15
    20 -> if (forward) forward20 else replay20
    25 -> if (forward) forward25 else replay25
    30 -> if (forward) Icons.Rounded.Forward30 else Icons.Rounded.Replay30
    else -> if (forward) Icons.Rounded.Forward10 else Icons.Rounded.Replay10
}

/** What a −/+ button says to a screen reader: "Back 15 seconds". [side] < 0 = back. */
@Composable
fun seekLabel(side: Int): String = SeekStep.seconds.let { n ->
    pluralStringResource(if (side < 0) R.plurals.ui_seek_back else R.plurals.ui_seek_forward, n, n.toString())
}

// The 10's arrow + "1", and the 5's "5" where the 10's "0" stands.
private val replay15 by lazy { seekGlyph("Rounded.Replay15", Icons.Rounded.Replay10, keep = listOf(0, 1), five = Icons.Rounded.Replay5 to 1.71f) }
private val forward15 by lazy { seekGlyph("Rounded.Forward15", Icons.Rounded.Forward10, keep = listOf(0, 1), five = Icons.Rounded.Forward5 to 1.77f) }
// The 30's arrow and "0", a "2" where its "3" stands.
private val replay20 by lazy { seekGlyph("Rounded.Replay20", Icons.Rounded.Replay30, keep = listOf(0, 2, 3), twoAt = 8.63f) }
private val forward20 by lazy { seekGlyph("Rounded.Forward20", Icons.Rounded.Forward30, keep = listOf(0, 2, 3), twoAt = 8.61f) }
// The 30's arrow, a "2" for its "3", and the 5's "5" where its "0" stands.
private val replay25 by lazy { seekGlyph("Rounded.Replay25", Icons.Rounded.Replay30, keep = listOf(0), twoAt = 8.63f, five = Icons.Rounded.Replay5 to 1.84f) }
private val forward25 by lazy { seekGlyph("Rounded.Forward25", Icons.Rounded.Forward30, keep = listOf(0), twoAt = 8.61f, five = Icons.Rounded.Forward5 to 1.89f) }

/**
 * [base]'s subpaths at [keep] (the arrow is 0, then each digit outline), plus a "2" whose left edge is at
 * [twoAt] and a Material "5" taken from [five]'s icon and shifted right by its number.
 */
private fun seekGlyph(name: String, base: ImageVector, keep: List<Int>, twoAt: Float? = null, five: Pair<ImageVector, Float>? = null): ImageVector {
    val parts = subpaths(base)
    val builder = ImageVector.Builder(name, base.defaultWidth, base.defaultHeight, base.viewportWidth, base.viewportHeight, autoMirror = false)
        .addPath(keep.flatMap { parts[it] }, fill = SolidColor(Color.Black))
    if (twoAt != null) {
        builder.addPath(
            twoAt(twoAt), stroke = SolidColor(Color.Black), strokeLineWidth = DigitStroke,
            strokeLineCap = StrokeCap.Butt, strokeLineJoin = StrokeJoin.Miter, strokeLineMiter = 4f,
        )
    }
    if (five != null) {
        builder.addGroup(translationX = five.second).addPath(subpaths(five.first)[1], fill = SolidColor(Color.Black)).clearGroup()
    }
    return builder.build()
}

/** Material's digits: 11.68 – 16.06 tall, 2.89 wide, strokes 0.84 thick. */
private const val DigitStroke = 0.84f

/** A "2" in the box [left] … [left] + 2.89 × 11.68 … 16.06, drawn as a 0.84 stroke along its middle. */
private fun twoAt(left: Float): List<PathNode> {
    val h = DigitStroke / 2
    val bottom = 16.06f - h
    return listOf(
        PathNode.MoveTo(left + 0.46f, 13.18f),
        PathNode.CurveTo(left + 0.46f, 12.45f, left + 0.95f, 12.1f, left + 1.46f, 12.1f),
        PathNode.CurveTo(left + 2.03f, 12.1f, left + 2.46f, 12.5f, left + 2.46f, 13.06f),
        PathNode.CurveTo(left + 2.46f, 13.47f, left + 2.27f, 13.77f, left + 1.97f, 14.1f),
        PathNode.LineTo(left + h + 0.03f, bottom),
        PathNode.LineTo(left + 2.89f, bottom),
    )
}

/** An icon's one path cut at each close: arrow first, then each digit's outline (each starts with an absolute move). */
private fun subpaths(icon: ImageVector): List<List<PathNode>> {
    val nodes = icon.root.filterIsInstance<VectorPath>().flatMap { it.pathData }
    val out = mutableListOf<List<PathNode>>()
    var current = mutableListOf<PathNode>()
    nodes.forEach { node ->
        current += node
        if (node == PathNode.Close) { out += current; current = mutableListOf() }
    }
    if (current.isNotEmpty()) out += current
    return out
}
