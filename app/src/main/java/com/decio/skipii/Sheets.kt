package com.decio.skipii

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WatchLater
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import android.view.inputmethod.InputMethodManager

// ── Long-press options ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoOptionsSheet(video: VideoItem, player: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(video.thumbnail, Modifier.width(112.dp).aspectRatio(16f / 9f), radius = 14)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(video.channel, color = c.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            }
        }
        OptionRow(Icons.Rounded.PlaylistPlay, "Play next") { player.playNext(video); onDismiss() }
        OptionRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to queue") { player.addToQueue(video); onDismiss() }
        if (Account.signedIn) {
            OptionRow(Icons.Rounded.WatchLater, "Save to Watch later") { player.saveToWatchLater(video); onDismiss() }
            OptionRow(Icons.Rounded.LibraryAdd, "Save to playlist") { onDismiss(); player.openPlaylists(video) }
        }
        OptionRow(Icons.Rounded.Share, "Share") {
            shareLink(context, "https://youtu.be/${video.id}", video.title)
            onDismiss()
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Save to playlist (GitHub issue #12) ─────────────────────────────────────────

/**
 * Every playlist on the account (Watch later first) with a check for the ones holding the
 * video; a tap adds or removes it there and then. "New playlist" makes one with the video in it.
 * Writes run in [PlayerState], so closing the sheet never cancels one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToPlaylistSheet(player: PlayerState, video: VideoItem) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = player::closePlaylists,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                "Save to playlist",
                fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                video.title, color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
            )
            val choices = player.playlistChoices
            val error = player.playlistError
            when {
                error != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = c.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    PillButton("Try again", { player.openPlaylists(video) })
                }
                choices == null -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(28.dp))
                }
                else -> {
                    choices.forEach { choice ->
                        PlaylistChoiceRow(choice) { player.setInPlaylist(video, choice, add = choice.contains != true) }
                    }
                    NewPlaylistRow { title, privacy -> player.createPlaylist(video, title, privacy) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistChoiceRow(choice: PlaylistChoice, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val on = choice.contains == true
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (choice.id == "WL") Icons.Rounded.WatchLater else Icons.Rounded.PlaylistPlay, null, tint = c.onSurface)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(choice.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            privacyLabel(choice.privacy)?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        }
        Spacer(Modifier.width(12.dp))
        // Filled check when the video is in it; an empty tonal circle when it isn't.
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (on) c.primary else c.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(Icons.Rounded.Check, "Saved", tint = c.onPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

private fun privacyLabel(privacy: String?) = when (privacy) {
    "PRIVATE" -> "Private"
    "UNLISTED" -> "Unlisted"
    "PUBLIC" -> "Public"
    else -> null
}

@Composable
private fun NewPlaylistRow(onCreate: (title: String, privacy: String) -> Unit) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    if (!open) {
        OptionRow(Icons.Rounded.Add, "New playlist") { open = true }
        return
    }
    var title by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf("PRIVATE") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    fun create() {
        val name = title.trim()
        if (name.isEmpty()) return
        // As on every field: Done closes the keyboard (the window-level hide comes later or the IME undoes it).
        focus.clearFocus(force = true)
        keyboard?.hide()
        view.postDelayed({ view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0) }, 120)
        onCreate(name, privacy)
        open = false
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(c.surfaceContainerHigh).padding(horizontal = 20.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (title.isEmpty()) Text("Playlist name", color = c.onSurfaceVariant, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            BasicTextField(
                value = title,
                onValueChange = { title = it.take(150) },
                singleLine = true,
                textStyle = OwnDirectionText.merge(TextStyle(color = c.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                keyboardActions = KeyboardActions(onDone = { create() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onPreviewKeyEvent { e ->
                    if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                        if (e.type == KeyEventType.KeyDown) create()
                        true
                    } else false
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("PRIVATE", "UNLISTED", "PUBLIC").forEach { key ->
                    val on = privacy == key
                    Box(
                        Modifier
                            .pressable { privacy = key }
                            .clip(RoundedCornerShape(if (on) 18.dp else 10.dp))
                            .background(if (on) c.secondaryContainer else c.surfaceContainerHighest)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) { Text(privacyLabel(key)!!, color = if (on) c.onSecondaryContainer else c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.width(8.dp))
            val ready = title.isNotBlank()
            Box(
                Modifier
                    .pressable(enabled = ready, onClick = ::create)
                    .clip(CircleShape)
                    .background(c.primary)
                    .graphicsLayer { alpha = if (ready) 1f else 0.4f }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) { Text("Create", color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.onSurface)
        Spacer(Modifier.width(18.dp))
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

// ── Queue ──────────────────────────────────────────────────────────────────────

/** Card under the player when something is queued; opens the queue sheet. */
@Composable
fun QueueCard(player: PlayerState, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val queue = player.queue
    // Once the queue empties (Clear, or its last video started), the card keeps what it
    // showed while it slides away and its space closes.
    var shown by remember { mutableStateOf(queue.firstOrNull()?.let { it to queue.size }) }
    LaunchedEffect(queue) { if (queue.isNotEmpty()) shown = queue.first() to queue.size }
    val leaving = queue.isEmpty()
    val (next, count) = queue.firstOrNull()?.let { it to queue.size } ?: shown ?: return
    val slide = remember { Animatable(0f) }
    val size = remember { Animatable(1f, visibilityThreshold = 0.0005f) }
    LaunchedEffect(leaving) {
        if (!leaving) { slide.snapTo(0f); size.snapTo(1f); return@LaunchedEffect }
        slide.animateTo(1f, tween(SlideAwayMs, easing = Motion.Exit))
        size.animateTo(0f, QueueCloseSpec)
        if (player.queue.isEmpty()) shown = null
    }
    SlideAway(slide = { slide.value }, size = { size.value }) {
        Row(
            Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .pressable(enabled = !leaving, pressedScale = 0.98f, onClick = onOpen)
                .clip(RoundedCornerShape(24.dp))
                .background(c.secondaryContainer)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(next.thumbnail, Modifier.width(88.dp).aspectRatio(16f / 9f), radius = 12)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Queue · $count",
                    color = c.onSecondaryContainer, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                )
                Text(next.title, color = c.onSecondaryContainer, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            // Its own tap: clearing doesn't open the sheet.
            Row(
                Modifier
                    .pressable(enabled = !leaving) { player.clearQueue() }
                    .clip(CircleShape)
                    .background(c.secondary)
                    .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ClearAll, null, tint = c.onSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear", color = c.onSecondary, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(player: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // Clear empties the queue at once; the rows it had stay on screen to slide away.
    var leavingRows by remember { mutableStateOf<List<VideoItem>?>(null) }
    /** Ms into the rows' staggered slide; on the animation clock so the close never starts early. */
    val sweep = remember { Animatable(0f) }
    val rows = leavingRows ?: player.queue
    /** 0 = the list, 1 = the empty state; between, the space closes from one height to the other. */
    val emptied = remember { Animatable(if (player.queue.isEmpty()) 1f else 0f, visibilityThreshold = 0.0005f) }
    LaunchedEffect(player.queue.isEmpty()) {
        if (leavingRows == null) emptied.animateTo(if (player.queue.isEmpty()) 1f else 0f, QueueCloseSpec)
    }
    val clearAlpha by animateFloatAsState(if (leavingRows == null && player.queue.isNotEmpty()) 1f else 0f, label = "clearAlpha")
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surfaceContainerLow) {
        Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, modifier = Modifier.weight(1f))
            Text(
                "Clear", color = c.primary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                modifier = Modifier
                    .graphicsLayer { alpha = clearAlpha }
                    .pressable(enabled = leavingRows == null && player.queue.isNotEmpty()) {
                        val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                        leavingRows = player.queue
                        player.clearQueue()
                        scope.launch {
                            val total = SlideAwayMs + SlideAwayStaggerMs * (visible - 1)
                            sweep.snapTo(0f)
                            sweep.animateTo(total.toFloat(), tween(total, easing = LinearEasing))
                            emptied.animateTo(1f, QueueCloseSpec)
                            leavingRows = null
                            sweep.snapTo(0f)
                        }
                    }
                    .padding(8.dp),
            )
        }
        Layout(
            content = {
                LazyColumn(Modifier.fillMaxHeight(0.7f), state = listState) {
                    itemsIndexed(rows, key = { i, v -> "$i-${v.id}" }) { index, video ->
                        // Top to bottom, one after another, counted from the first row on screen.
                        val order = (index - listState.firstVisibleItemIndex).coerceAtLeast(0)
                        SlideAway(slide = { Motion.Exit.transform(((sweep.value - SlideAwayStaggerMs * order) / SlideAwayMs).coerceIn(0f, 1f)) }) {
                            Box(Modifier.padding(horizontal = 20.dp)) {
                                VideoRow(video, onClick = { player.playFromQueue(index); onDismiss() }) {
                                    Box(Modifier.size(40.dp).pressable { player.removeFromQueue(index) }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Close, "Remove from queue", tint = c.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    "Nothing queued. Press and hold a video to add it.",
                    color = c.onSurfaceVariant, fontSize = 14.sp,
                    modifier = Modifier.graphicsLayer { alpha = emptied.value }.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            },
        ) { measurables, constraints ->
            val list = measurables[0].measure(constraints)
            val empty = measurables[1].measure(constraints.copy(minHeight = 0))
            val t = emptied.value
            val height = (list.height + (empty.height - list.height) * t).roundToInt()
            layout(constraints.maxWidth, height.coerceAtLeast(0)) {
                if (t < 1f) list.placeRelative(0, 0)
                empty.placeRelative(0, 0)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private const val SlideAwayMs = 300
private const val SlideAwayStaggerMs = 40
private val QueueCloseSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.0005f)

/**
 * Clearing the queue: [content] slides right by [slide] (0..1, fully past the edge at 1)
 * and keeps [size] of its height, so the space it leaves can close smoothly. Both are read
 * per frame in layout only.
 */
@Composable
private fun SlideAway(slide: () -> Float, size: () -> Float = { 1f }, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints)
        layout(placeable.width, (placeable.height * size()).roundToInt()) {
            // Past the right edge entirely, padding and shadow included.
            placeable.placeRelativeWithLayer(0, 0) { translationX = slide() * (placeable.width + 24.dp.toPx()) }
        }
    }
}

// ── SponsorBlock settings (inside the account sheet) ───────────────────────────

@Composable
fun SponsorBlockSettings() {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        SettingSwitchRow("SponsorBlock", "Skip sponsor reads and other segments", SponsorBlock.enabled, SponsorBlock::updateEnabled)
        if (SponsorBlock.enabled) {
            Spacer(Modifier.height(10.dp))
            SponsorBlock.Category.entries.forEach { category -> CategoryRow(category) }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Segments from SponsorBlock (sponsor.ajay.app), a community project. Data licensed CC BY-NC-SA 4.0.",
            color = c.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 16.sp,
        )
    }
}

@Composable
private fun CategoryRow(category: SponsorBlock.Category) {
    val c = MaterialTheme.colorScheme
    val current = SponsorBlock.actionFor(category)
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(category.color))
            Spacer(Modifier.width(8.dp))
            Text(category.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SponsorBlock.Action.entries.forEach { action ->
                val on = action == current
                Box(
                    Modifier
                        .pressable { SponsorBlock.setAction(category, action) }
                        .clip(RoundedCornerShape(if (on) 18.dp else 10.dp))
                        .background(if (on) c.secondaryContainer else c.surfaceContainerHighest)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(action.label, color = if (on) c.onSecondaryContainer else c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

// ── Shorts (inside the account sheet) ─────────────────────────────────────────

/** GitHub issue #21: the Shorts tab can go (and with it its feed and the shorts kept on disk). */
@Composable
fun ShortsSettings() {
    val context = LocalContext.current
    SettingSwitchRow(
        "Shorts",
        if (ShortsSetting.enabled) "Show the Shorts tab" else "The Shorts tab is hidden and no Shorts are loaded",
        ShortsSetting.enabled,
    ) { ShortsSetting.set(context, it) }
}

/**
 * A setting's title and line, with its switch. The layout is the English one in every language
 * (supportsRtl="false"), except this: on an RTL phone (Arabic) the switch sits LEFT of the words and
 * is mirrored (on = thumb to the left), as in Android's own Settings there (checked 2026-10-30, Display
 * › Dark theme in Arabic) and as Derek allowed.
 */
@Composable
private fun SettingSwitchRow(title: String, line: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    val rtl = rememberRtlLanguage()
    val toggle = @Composable {
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedTrackColor = c.primary))
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (rtl) {
            toggle()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Text(line, color = c.onSurfaceVariant, fontSize = 13.sp)
        }
        if (!rtl) toggle()
    }
}

// ── Appearance (inside the account sheet) ──────────────────────────────────────

/** Light/dark/AMOLED mode plus every Ledgii theme (and wallpaper colors on Android 12+). */
@Composable
fun AppearanceSettings() {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val dark = isDarkTheme()
    Column(Modifier.fillMaxWidth()) {
        Text("Appearance", fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        Spacer(Modifier.height(10.dp))
        // Scrolls like the rows below: a fixed Row squeezed the last chip to its padding on a big
        // font / display size, and its label wrapped a letter a line into a tall empty bar (#24).
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("system" to "System", "light" to "Light", "dark" to "Dark", "amoled" to "AMOLED").forEach { (key, label) ->
                val on = ThemeSettings.mode == key
                Box(
                    Modifier
                        .pressable { ThemeSettings.chooseMode(key) }
                        .clip(RoundedCornerShape(if (on) 18.dp else 10.dp))
                        .background(if (on) c.secondaryContainer else c.surfaceContainerHighest)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) { Text(label, color = if (on) c.onSecondaryContainer else c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false) }
            }
        }
        Spacer(Modifier.height(14.dp))
        val choices = buildList {
            if (ThemeSettings.wallpaperAvailable) add(null to "Wallpaper")
            SKIPII_THEMES.forEach { add(it.hex to it.name) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            choices.forEach { (hex, name) ->
                val selected = ThemeSettings.seed?.lowercase() == hex?.lowercase()
                val scheme = themedScheme(context, hex, dark)
                Column(
                    Modifier.width(78.dp).pressable { ThemeSettings.choose(hex) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(if (selected) CookieShape else CircleShape)
                            .background(scheme.surface)
                            .padding(5.dp)
                            .clip(if (selected) CookieShape else CircleShape)
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    0f to scheme.primary, 0.5f to scheme.primary,
                                    0.5f to scheme.primaryContainer, 1f to scheme.primaryContainer,
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, "Selected", tint = scheme.onPrimary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        name, fontSize = 11.5.sp, maxLines = 1, softWrap = false,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (selected) c.onSurface else c.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
