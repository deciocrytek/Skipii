package com.decio.skipii

import android.view.accessibility.CaptioningManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ── On the video ──────────────────────────────────────────────────────────────

/**
 * The captions over the picture: centred, [bottom] above its bottom edge (the caller lifts them clear of
 * the controls), never wider than 92% of it. Sized to the picture (as YouTube's are), times the chosen
 * size. Lines show whole and change with a hard cut, no animation: auto-generated ones two at a time
 * ([captionPages]). The lines are recomputed every frame while playing.
 */
@Composable
fun CaptionLayer(state: PlayerState, bottom: Dp, modifier: Modifier = Modifier, alpha: () -> Float = { 1f }) {
    val cap = state.captions
    if (!cap.on) return
    val choice = cap.choice ?: return
    val cues = cap.cues
    val pages = remember(cues, choice) { cues?.takeIf { choice.track.auto }?.let(::captionPages) }
    var lines by remember(cues) { mutableStateOf(emptyList<CaptionLine>()) }
    if (cues != null) LaunchedEffect(cues, pages) {
        var frame = 0
        while (true) {
            withFrameMillis { }
            // Paused, the position only moves on seeks: a few checks a second are plenty.
            if (!state.isPlaying && frame++ % 6 != 0) continue
            val t = if (state.isPlaying) state.controller?.currentPosition ?: state.positionMs else state.positionMs
            val now = if (pages != null) captionPageAt(pages, t) else captionLinesAt(cues, t)
            if (now != lines) lines = now
        }
    }
    val look = rememberCaptionLook()
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha() }) {
        // About 15 dp on the portrait video, 20 in fullscreen, small in picture-in-picture.
        val px = (maxHeight.value * 0.065f).coerceIn(9f, 20f) * CaptionSettings.size * look.scale
        Column(
            Modifier.fillMaxSize().padding(bottom = bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy((px * 0.16f).dp, Alignment.Bottom),
        ) {
            if (cues == null) CaptionStatus(cap, choice, px)
            else if (lines.isNotEmpty()) {
                Box(Modifier.widthIn(max = this@BoxWithConstraints.maxWidth * 0.92f)) { CaptionText(lines, look, px) }
            }
        }
    }
}

/** What's keeping the captions: a translation model downloading, a translation starting, or a failure (for a while). */
@Composable
private fun CaptionStatus(cap: CaptionsState, choice: CaptionChoice, px: Float) {
    val error = cap.error
    var showError by remember(error) { mutableStateOf(error != null) }
    LaunchedEffect(error) { if (error != null) { delay(4_000); showError = false } }
    val text = when {
        error != null -> error.takeIf { showError }
        choice.translateTo == null -> null
        CaptionTranslator.downloading.isNotEmpty() -> stringResource(R.string.captions_downloading_language, choice.label)
        cap.loading -> stringResource(R.string.captions_translating_into, choice.label)
        else -> null
    } ?: return
    Text(
        text,
        color = Color.White, fontSize = with(LocalDensity.current) { (px * 0.78f).dp.toSp() }, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(Color(0xFF120F1A).copy(alpha = 0.72f)).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** How caption text is drawn ([CaptionSettings.Look]); [scale] multiplies its size (Android's caption settings have their own). */
@Immutable
private class CaptionLook(
    val ink: Color,
    val box: Color,
    /** Box corner and side padding, in em. */
    val corner: Float,
    val pad: Float,
    val weight: FontWeight,
    val family: FontFamily?,
    val outline: Color? = null,
    val shadow: Shadow? = null,
    val scale: Float = 1f,
)

@Composable
private fun rememberCaptionLook(): CaptionLook {
    val context = LocalContext.current
    return when (CaptionSettings.look) {
        CaptionSettings.Look.Skipii -> CaptionLook(Color.White, Color(0xFF120F1A).copy(alpha = 0.74f), 0.42f, 0.42f, FontWeight.Bold, null)
        CaptionSettings.Look.Classic -> CaptionLook(Color.White, Color(0xFF080808).copy(alpha = 0.75f), 0f, 0.25f, FontWeight.Medium, FontFamily.SansSerif)
        CaptionSettings.Look.Outline -> CaptionLook(Color.White, Color.Transparent, 0f, 0.1f, FontWeight.ExtraBold, null, outline = Color.Black)
        // Android's Settings › Accessibility › Caption preferences.
        CaptionSettings.Look.System -> remember(context) {
            val manager = context.getSystemService(CaptioningManager::class.java)
            val style = manager?.userStyle
            val ink = if (style?.hasForegroundColor() == true) Color(style.foregroundColor) else Color.White
            val box = if (style?.hasBackgroundColor() == true) Color(style.backgroundColor) else Color.Black
            val edge = if (style?.hasEdgeColor() == true) Color(style.edgeColor) else Color.Black
            val type = if (style?.hasEdgeType() == true) style.edgeType else CaptioningManager.CaptionStyle.EDGE_TYPE_NONE
            CaptionLook(
                ink = ink, box = box, corner = 0f, pad = 0.25f, weight = FontWeight.Medium,
                family = style?.typeface?.let { FontFamily(androidx.compose.ui.text.font.Typeface(it)) },
                outline = edge.takeIf { type == CaptioningManager.CaptionStyle.EDGE_TYPE_OUTLINE },
                shadow = when (type) {
                    CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW -> Shadow(edge, Offset(3f, 3f), 4f)
                    CaptioningManager.CaptionStyle.EDGE_TYPE_RAISED -> Shadow(edge, Offset(-1.5f, -1.5f), 1f)
                    CaptioningManager.CaptionStyle.EDGE_TYPE_DEPRESSED -> Shadow(edge, Offset(1.5f, 1.5f), 1f)
                    else -> null
                },
                scale = manager?.fontScale ?: 1f,
            )
        }
    }
}

/**
 * The lines on screen as ONE block (Derek: the two lines connect, no gap between them): one text, a box behind
 * each visual line, the boxes touching. Corners are rounded only where a line sticks out past its neighbour, so
 * the boxes join into one shape (no notch at the seam), and they're one path, filled once (no darker seam).
 */
@Composable
private fun CaptionText(lines: List<CaptionLine>, look: CaptionLook, px: Float) {
    val density = LocalDensity.current
    val size = with(density) { px.dp.toSp() }
    val style = TextStyle(
        color = look.ink,
        fontSize = size,
        lineHeight = 1.3.em,
        fontWeight = look.weight,
        fontFamily = look.family,
        textAlign = TextAlign.Center,
        shadow = look.shadow,
    )
    val text = remember(lines) {
        buildAnnotatedString {
            lines.forEachIndexed { i, line ->
                if (i > 0) append('\n')
                if (line.sound) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(line.text) } else append(line.text)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // One em, in pixels.
    val em = with(density) { px.dp.toPx() }
    Box(
        Modifier
            .padding(horizontal = (px * look.pad).dp)
            .drawBehind {
                val l = layout ?: return@drawBehind
                if (look.box.alpha == 0f) return@drawBehind
                val pad = look.pad * em
                val r = look.corner * em
                // Each visual line's box (empty lines have none).
                val boxes = (0 until l.lineCount).mapNotNull { i ->
                    val left = l.getLineLeft(i)
                    val right = l.getLineRight(i)
                    if (right <= left) null else Rect(left - pad, l.getLineTop(i), right + pad, l.getLineBottom(i))
                }
                val shape = Path()
                boxes.forEachIndexed { i, box ->
                    val above = boxes.getOrNull(i - 1)
                    val below = boxes.getOrNull(i + 1)
                    // A corner is round only where this box reaches past the one it touches there.
                    fun round(out: Boolean) = if (out) CornerRadius(r) else CornerRadius.Zero
                    shape.addRoundRect(
                        RoundRect(
                            // A pixel into the box below, so antialiasing never opens a hairline between them.
                            rect = if (below != null) box.copy(bottom = box.bottom + 1f) else box,
                            topLeft = round(above == null || box.left < above.left - 0.5f),
                            topRight = round(above == null || box.right > above.right + 0.5f),
                            bottomRight = round(below == null || box.right > below.right + 0.5f),
                            bottomLeft = round(below == null || box.left < below.left - 0.5f),
                        ),
                    )
                }
                drawPath(shape, look.box)
            },
    ) {
        look.outline?.let { edge ->
            Text(text, style = style.copy(color = edge, shadow = null, drawStyle = Stroke(width = em * 0.16f, join = androidx.compose.ui.graphics.StrokeJoin.Round)))
        }
        Text(text, style = style, onTextLayout = { layout = it })
    }
}

// ── The list of what can be shown ─────────────────────────────────────────────

/** A row in a captions list (the bar's panel, fullscreen's card, the sheet). */
@Immutable
sealed interface CaptionOption {
    /** A track YouTube has, or a translation made on the phone ([ready]: its models are here; [busy]: downloading). */
    data class Pick(val choice: CaptionChoice, val title: String, val note: String?, val ready: Boolean = true, val busy: Boolean = false) : CaptionOption
    /** "Translated on your phone": the translations follow. */
    data object Section : CaptionOption
    /** Every language ML Kit can translate into, not just the likely ones. */
    data object More : CaptionOption
}

/**
 * The video's tracks (your language first), then translations: into the app's language, the last one
 * picked and any already downloaded; with [all], every language. Languages the creator already has a
 * track in aren't offered as translations.
 */
fun captionOptions(cap: CaptionsState, all: Boolean): List<CaptionOption> = buildList {
    cap.tracks.forEach { add(CaptionOption.Pick(CaptionChoice(it), it.title(cap.tracks), if (it.auto) str(R.string.captions_auto_generated) else null)) }
    val source = cap.translationSource ?: return@buildList
    val from = CaptionTranslator.code(source.base) ?: return@buildList
    val covered = cap.tracks.filter { !it.auto }.mapNotNull { CaptionTranslator.code(it.base) }.toSet() + from
    val likely = (listOfNotNull(CaptionTranslator.code(CaptionSettings.appLanguage), CaptionTranslator.code(CaptionSettings.language), cap.choice?.translateTo) +
        CaptionTranslator.downloaded.sortedBy { CaptionNames.language(it) }).distinct()
    val targets = (if (all) likely + CaptionTranslator.all.sortedBy { CaptionNames.language(it) } else likely).distinct().filter { it !in covered }
    val more = !all && CaptionTranslator.all.any { it !in covered && it !in likely }
    if (targets.isEmpty() && !more) return@buildList
    add(CaptionOption.Section)
    targets.forEach { lang ->
        val ready = CaptionTranslator.ready(from, lang)
        val busy = from in CaptionTranslator.downloading || lang in CaptionTranslator.downloading
        add(
            CaptionOption.Pick(
                CaptionChoice(source, lang), CaptionNames.language(lang),
                when {
                    busy -> str(R.string.captions_note_downloading)
                    ready -> str(R.string.captions_note_from, CaptionNames.language(source.base))
                    else -> str(R.string.captions_note_download_size)
                },
                ready = ready, busy = busy,
            ),
        )
    }
    if (more) add(CaptionOption.More)
}

/** Picked [option] is what's showing now. */
fun CaptionsState.shows(option: CaptionOption.Pick) = on && choice == option.choice

// ── Sheet (portrait, controls on the video) ───────────────────────────────────

/** The captions list as a sheet, for the controls on the video (the pill shows it in the bar, fullscreen in a card). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionsSheet(state: PlayerState, onDismiss: () -> Unit, onTranscript: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val cap = state.captions
    var all by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.captions_title), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, modifier = Modifier.weight(1f))
                Switch(cap.on, onCheckedChange = cap::turn, enabled = cap.choice != null, colors = SwitchDefaults.colors(checkedTrackColor = c.primary))
            }
            if (cap.tracks.isEmpty()) {
                Text(stringResource(R.string.captions_none), color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(4.dp))
            }
            captionOptions(cap, all).forEach { option ->
                when (option) {
                    is CaptionOption.Pick -> CaptionSheetRow(option.title, option.note, cap.shows(option)) { cap.pick(option.choice); onDismiss() }
                    CaptionOption.Section -> Text(
                        stringResource(R.string.captions_translated_on_phone), color = c.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
                    )
                    CaptionOption.More -> CaptionSheetRow(stringResource(R.string.captions_more_languages), null, false) { all = true }
                }
            }
            if (cap.tracks.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().height(48.dp).pressable(onClick = onTranscript).clip(CircleShape).background(c.surfaceContainerHigh),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Subtitles, null, tint = c.onSurface, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.captions_transcript), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CaptionSheetRow(title: String, note: String?, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .pressable(pressedScale = 0.97f, onClick = onClick)
            .clip(CircleShape)
            .background(if (selected) c.primary else c.primary.copy(alpha = 0f))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = if (selected) c.onPrimary else c.onSurface, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        if (note != null) {
            Text(" · $note", color = if (selected) c.onPrimary.copy(alpha = 0.8f) else c.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.captions_selected), tint = c.onPrimary, modifier = Modifier.size(20.dp))
    }
}

// ── Transcript ────────────────────────────────────────────────────────────────

/**
 * Every line of the chosen track (or the best one, with captions off), in the comments' panel. The line
 * being said is filled and its words light up as they're said; the list follows along until it's scrolled,
 * then "Back to now" brings it back. A tap jumps there. Search narrows the list to the lines that match.
 */
@Composable
fun TranscriptPanel(state: PlayerState, partialTop: Dp, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val cap = state.captions
    DisposableEffect(cap) {
        cap.setTranscript(true)
        onDispose { cap.setTranscript(false) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    PlayerPanel(
        partialTop = partialTop,
        onClose = onClose,
        closeLabel = stringResource(R.string.captions_close_transcript),
        title = {
            Text(stringResource(R.string.captions_transcript), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
            cap.choice?.let { choice ->
                Spacer(Modifier.width(10.dp))
                Text(
                    choice.label + if (choice.track.auto && choice.translateTo == null) " · " + stringResource(R.string.captions_auto) else "",
                    color = c.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.clip(CircleShape).background(c.surfaceContainerHigh).padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        },
    ) { listModifier ->
        TranscriptSearch(query) { query = it }
        val cues = cap.cues
        Box(listModifier) {
            when {
                cap.tracks.isEmpty() -> TranscriptNote(stringResource(R.string.captions_no_transcript))
                cues == null && cap.error != null -> TranscriptNote(cap.error.orEmpty())
                cues == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.TopCenter) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                }
                cues.isEmpty() -> TranscriptNote(stringResource(R.string.captions_no_lines))
                else -> TranscriptList(state, cues, query.trim()) { query = "" }
            }
        }
        Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun TranscriptNote(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
}

@Composable
private fun TranscriptSearch(query: String, onChange: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(46.dp).clip(CircleShape).background(c.surfaceContainerHigh).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.captions_search_transcript), color = c.onSurfaceVariant, fontSize = 15.sp)
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = c.onSurface, fontSize = 15.sp).merge(OwnDirectionText),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Rule 4: Search closes the keyboard.
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(36.dp).pressable { onChange("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, stringResource(R.string.captions_clear_search), tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun TranscriptList(state: PlayerState, cues: List<CaptionCue>, query: String, onJumped: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val now = state.positionMs
    val current = cueIndexAt(cues, now)
    val shown = remember(cues, query) {
        cues.indices.filter { query.isEmpty() || cues[it].text.contains(query, ignoreCase = true) }
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (shown.indexOfFirst { it >= current } - 2).coerceAtLeast(0))
    var following by remember { mutableStateOf(true) }
    // A drag on the list stops following (a scroll the list does itself isn't a nested scroll).
    val dragged = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) following = false
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero
        }
    }
    LaunchedEffect(current, following, shown) {
        if (!following || query.isNotEmpty()) return@LaunchedEffect
        val at = shown.indexOfFirst { it >= current }.takeIf { it >= 0 } ?: return@LaunchedEffect
        list.animateScrollToItem((at - 2).coerceAtLeast(0))
    }
    Box(Modifier.fillMaxSize()) {
        if (shown.isEmpty()) TranscriptNote(stringResource(R.string.captions_nothing_matches, query))
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().nestedScroll(dragged),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 96.dp),
        ) {
            itemsIndexed(shown, key = { _, i -> i }) { _, i ->
                val cue = cues[i]
                val playing = i == current && now < cue.endMs + 1_500
                TranscriptRow(cue, playing, past = i < current, now = now, query = query) {
                    state.seekTo(cue.startMs)
                    following = true
                    if (query.isNotEmpty()) onJumped()
                }
            }
        }
        val reduced = rememberReducedMotion()
        val pill by animateFloatAsState(if (!following && query.isEmpty()) 1f else 0f, if (reduced) tween(0) else tween(220, easing = Motion.Emphasized), label = "backToNow")
        if (pill > 0f) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp)
                    .graphicsLayer { alpha = pill; translationY = (1f - pill) * 16.dp.toPx() }
                    .pressable { following = true }
                    .clip(CircleShape)
                    .background(c.primary)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.onPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.captions_back_to_now), color = c.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp)
            }
        }
    }
}

@Composable
private fun TranscriptRow(cue: CaptionCue, playing: Boolean, past: Boolean, now: Long, query: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val text = remember(cue, playing, if (playing) now / 100 else 0L, query) {
        transcriptText(cue, playing, now, query, said = c.onSurface, ahead = c.onSurfaceVariant, mark = c.primary.copy(alpha = 0.28f))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .clip(RoundedCornerShape(18.dp))
            .background(if (playing) c.surfaceContainerHigh else c.surfaceContainerHigh.copy(alpha = 0f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            formatDuration(cue.startMs),
            color = if (playing) c.primary else c.onSurfaceVariant,
            fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.width(52.dp).padding(top = 2.dp),
        )
        Text(
            text,
            color = if (past || playing) c.onSurface else c.onSurfaceVariant,
            fontSize = 15.sp, lineHeight = 21.sp,
            fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium,
            fontStyle = if (cue.sound) FontStyle.Italic else FontStyle.Normal,
        )
    }
}

/** The line's text: the words said so far in full ink while it's playing, and search matches marked. */
private fun transcriptText(cue: CaptionCue, playing: Boolean, now: Long, query: String, said: Color, ahead: Color, mark: Color): AnnotatedString =
    buildAnnotatedString {
        val text = cue.text
        if (playing) {
            // Auto-generated lines know each word's time; others light up evenly over the line.
            val upTo = cue.words?.let { words ->
                words.filter { cue.startMs + it.offsetMs <= now }.joinToString("") { it.text }.trim().length
            } ?: run {
                val f = ((now - cue.startMs).toFloat() / (cue.endMs - cue.startMs - 300).coerceAtLeast(1)).coerceIn(0f, 1f)
                val words = text.split(' ')
                words.take((words.size * f).toInt()).sumOf { it.length + 1 }
            }
            val cut = upTo.coerceIn(0, text.length)
            withStyle(SpanStyle(color = said)) { append(text.substring(0, cut)) }
            withStyle(SpanStyle(color = ahead)) { append(text.substring(cut)) }
        } else {
            append(text)
        }
        if (query.isNotEmpty()) {
            var from = 0
            while (true) {
                val at = text.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                addStyle(SpanStyle(background = mark), at, at + query.length)
                from = at + query.length
            }
        }
    }

// ── Settings › Captions ───────────────────────────────────────────────────────

/** A still with a sample line in the chosen look and size (Settings › Captions). */
@Composable
fun CaptionPreview() {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 7f)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF2B2350), Color(0xFF7B3F6E), Color(0xFFE07A5F), Color(0xFF1D1A2C)))),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val look = rememberCaptionLook()
            // As big as on the portrait video, whatever this box's height.
            val px = 15f * CaptionSettings.size * look.scale
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).widthIn(max = maxWidth * 0.92f)) {
                val sample = listOf(
                    CaptionLine(stringResource(R.string.captions_sample_line_1), false),
                    CaptionLine(stringResource(R.string.captions_sample_line_2), false),
                )
                CaptionText(sample, look, px)
            }
        }
    }
}
