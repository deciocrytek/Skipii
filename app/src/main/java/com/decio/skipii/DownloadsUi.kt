package com.decio.skipii

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The download notification asked for the Downloads page (MainActivity opens it once the app is up). */
object OpenDownloads {
    var requested by mutableStateOf(false)
    /** The Search widget's tap: the Search tab, its field focused. */
    var search by mutableStateOf(false)
}

/** A size the way the phone writes it ("412 MB", "1.4 GB"). */
@Composable
fun bytesLabel(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))

/** Opens the Downloads page (MainActivity provides it); null where there's none. */
val LocalDownloadsOpener = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/** What a download's row / pill says about it now. */
@Composable
private fun statusLine(e: Downloads.Entry): String = when {
    e.ready -> if (e.audioOnly) listOf(stringResource(R.string.dl_audio), bytesLabel(e.totalBytes)).joinDots()
    else listOf("${e.height}p", bytesLabel(e.totalBytes)).joinDots()
    e.status == Downloads.Status.Failed -> stringResource(R.string.dl_failed)
    e.status == Downloads.Status.Downloading && !Downloads.waitingForWifi -> stringResource(R.string.dl_downloading, (e.progress * 100).toInt().toString())
    Downloads.waitingForWifi -> stringResource(R.string.dl_waiting_wifi)
    e.auto && Downloads.autoFull -> stringResource(R.string.dl_waiting_room)
    else -> stringResource(R.string.dl_queued)
}

/**
 * The Download sheet (Neon's spec): the sizes this video can be kept at (each height up to 1080p it has, then sound only),
 * the button with the size. A video already downloaded (or on its way) shows that instead, with Delete / Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(video: VideoItem, player: PlayerState, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val entry = Downloads.entry(video.id)
    var choices by remember(video.id) { mutableStateOf<List<Downloads.Choice>?>(null) }
    var failed by remember(video.id) { mutableStateOf(false) }
    LaunchedEffect(video.id, entry == null) {
        if (entry != null) return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { BotCheck.withoutWaiting(video.id) { StreamCache.getOrLoad(video.id) } } }
            .onSuccess { info -> choices = Downloads.choices(info); failed = choices.isNullOrEmpty() }
            .onFailure { failed = true }
    }
    var picked by remember(video.id) { mutableStateOf<Int?>(-1) }
    // The first pick: 720p (or the tallest under it), the automatic quality's default.
    LaunchedEffect(choices) {
        val list = choices ?: return@LaunchedEffect
        if (picked == -1) picked = list.filter { it.height != null }.let { v -> (v.firstOrNull { (it.height ?: 0) <= 720 } ?: v.lastOrNull())?.height }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.dl_sheet_title), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Text(video.title, color = c.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp))
            Text(stringResource(R.string.dl_sheet_line), color = c.onSurfaceVariant, fontSize = 12.5.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp))
            if (entry != null) {
                DownloadStatusCard(entry)
                SheetButton(
                    stringResource(if (entry.ready) R.string.dl_delete else R.string.dl_cancel), Icons.Rounded.Delete, tonal = true,
                ) { onDismiss(); deleteWithUndo(entry.id, player) }
                return@Column
            }
            val list = choices
            when {
                failed -> Text(stringResource(R.string.dl_sheet_unavailable), color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
                list == null -> Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 3.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.dl_sheet_loading), color = c.onSurfaceVariant, fontSize = 14.sp)
                }
                else -> {
                    fun size(ch: Downloads.Choice) = ch.bytes.takeIf { it > 0 }
                    val videos = list.filter { it.height != null }
                    if (videos.isNotEmpty()) SheetLabel(stringResource(R.string.dl_video))
                    videos.forEach { ch ->
                        QualityRow("${ch.height}p", size(ch)?.let { sizeText(it, ch.estimated) }, picked == ch.height) { picked = ch.height }
                    }
                    SheetLabel(stringResource(R.string.dl_sound_only))
                    list.firstOrNull { it.height == null }?.let { ch ->
                        QualityRow(
                            stringResource(R.string.dl_audio_only),
                            listOfNotNull(size(ch)?.let { sizeText(it, ch.estimated) }, stringResource(R.string.dl_audio_only_line)).joinDots(),
                            picked == null,
                        ) { picked = null }
                    }
                    val chosen = list.firstOrNull { it.height == picked }
                    val label = if (Downloads.waitingForWifi) stringResource(R.string.dl_button_wifi)
                    else listOfNotNull(stringResource(R.string.dl_action), chosen?.bytes?.takeIf { it > 0 }?.let { bytesLabel(it) }).joinDots()
                    SheetButton(label, Icons.Rounded.Download, enabled = chosen != null) {
                        Downloads.request(video, picked)
                        player.actionMessage = str(if (Downloads.waitingForWifi) R.string.dl_started_wifi else R.string.dl_started)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun sizeText(bytes: Long, estimated: Boolean): String = bytesLabel(bytes).let { if (estimated) stringResource(R.string.dl_size_about, it) else it }

/** A download in the sheet: its state and how far along. */
@Composable
private fun DownloadStatusCard(e: Downloads.Entry) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceContainer).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (e.ready) Icons.Rounded.DownloadDone else if (Downloads.waitingForWifi) Icons.Rounded.Wifi else Icons.Rounded.Download, null, tint = c.primary)
            Spacer(Modifier.width(10.dp))
            Text(statusLine(e), fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        if (!e.ready) {
            Spacer(Modifier.height(10.dp))
            ProgressLine(e.progress)
        }
    }
}

@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val shown by animateFloatAsState(progress, tween(240, easing = Motion.Emphasized), label = "dlProgress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(c.primary))
    }
}

@Composable
private fun SheetButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tonal: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp).fillMaxWidth().height(48.dp)
            .pressable(enabled = enabled, onClick = onClick)
            .clip(CircleShape).background(if (tonal) c.surfaceContainerHigh else c.primary),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val ink = if (tonal) c.onSurface else c.onPrimary
        Icon(icon, null, tint = ink, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** Takes a download off the phone, with an Undo for a few seconds (its bytes stay until the Undo has had its time). */
fun deleteWithUndo(id: String, player: PlayerState) {
    val gone = Downloads.remove(id) ?: return
    val message = str(R.string.dl_deleted)
    player.actionMessage = message
    player.actionUndo = message to { Downloads.restore(gone) }
}

/** Library's Downloads tile (Neon's spec): 78 dp, secondary container, a cookie badge; how many and how much. */
@Composable
fun DownloadsTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val ready = Downloads.entries.values.count { it.ready }
    val busy = Downloads.entries.values.firstOrNull { it.status == Downloads.Status.Downloading }
    Row(
        modifier
            .fillMaxWidth()
            .height(78.dp)
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .clip(RoundedCornerShape(24.dp))
            .background(c.secondaryContainer)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CookieShape).background(c.secondary), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.DownloadDone, null, tint = c.onSecondary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.dl_title), color = c.onSecondaryContainer, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            Text(
                when {
                    busy != null -> statusLine(busy)
                    ready > 0 -> listOf(pluralStringResource(R.plurals.dl_count, ready, ready.toString()), bytesLabel(Downloads.usedBytes)).joinDots()
                    else -> stringResource(R.string.dl_library_line)
                },
                color = c.onSecondaryContainer.copy(alpha = 0.78f), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.onSecondaryContainer.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

private enum class DownloadFilter(val labelRes: Int) { All(R.string.dl_filter_all), Video(R.string.dl_filter_video), Audio(R.string.dl_filter_audio), Auto(R.string.dl_filter_auto) }

/**
 * Library › Downloads (a page over the tabs, like History): the space used, filters, then every download newest first —
 * swipe one left to delete it (Undo), tap a ready one to play it from the phone, a failed one to try again.
 */
@Composable
fun DownloadsScreen(contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (VideoItem) -> Unit, onSettings: () -> Unit, player: PlayerState) {
    val c = MaterialTheme.colorScheme
    var filter by rememberSaveable { mutableStateOf(DownloadFilter.All) }
    val all = Downloads.entries.values.sortedByDescending { it.addedAt }
    val shown = all.filter {
        when (filter) {
            DownloadFilter.All -> true
            DownloadFilter.Video -> !it.audioOnly
            DownloadFilter.Audio -> it.audioOnly
            DownloadFilter.Auto -> it.auto
        }
    }
    Box(Modifier.fillMaxSize().background(c.surface)) {
        CollapsingHeaderLayout(
            resetKey = filter,
            header = {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), onBack)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.dl_title), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, letterSpacing = (-0.8).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    CircleAction(Icons.Rounded.Tune, stringResource(R.string.dl_settings), onSettings)
                }
            },
        ) { top ->
            LazyColumn(contentPadding = contentPadding.withTop(top)) {
                if (all.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Rounded.DownloadDone,
                            title = stringResource(R.string.dl_empty_title),
                            body = stringResource(R.string.dl_empty_body),
                            action = { PillButton(stringResource(R.string.dl_settings), onSettings) },
                        )
                    }
                    return@LazyColumn
                }
                item(key = "storage") { StorageMeter() }
                item(key = "filters") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        items(DownloadFilter.entries) { f -> FilterPill(stringResource(f.labelRes), selected = filter == f) { filter = f } }
                    }
                }
                if (shown.isEmpty()) item(key = "none") {
                    Text(stringResource(R.string.dl_empty_filtered), color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(24.dp))
                }
                items(shown, key = { it.id }) { e ->
                    SwipeVideo(
                        e.item, modifier = Modifier.animateItem(), inset = VideoRowInset, corner = VideoRowCorner,
                        onSwipeLeft = { deleteWithUndo(e.id, player) },
                    ) {
                        DownloadRow(e) {
                            when {
                                e.status == Downloads.Status.Failed -> Downloads.retry(e.id)
                                else -> onOpen(Downloads.withLocalThumb(e.item))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageMeter() {
    val c = MaterialTheme.colorScheme
    val used = Downloads.usedBytes
    val auto = Downloads.autoBytes
    val limit = Downloads.limitGb * 1_000_000_000L
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.dl_used, bytesLabel(used)), fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (auto > 0 || Downloads.autoQueue || Downloads.autoWatchLater) {
                Text(stringResource(R.string.dl_auto_used, bytesLabel(auto), stringResource(R.string.dl_gb, Downloads.limitGb.toString())), color = c.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        // Automatic downloads against their space (full primary), the rest after them (primary at 42%).
        val autoShare by animateFloatAsState((auto.toFloat() / limit).coerceIn(0f, 1f), tween(240, easing = Motion.Emphasized), label = "autoShare")
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.surfaceContainerHigh).drawBehind {
                val w = size.width * autoShare
                drawRect(c.primary, size = Size(w, size.height))
            },
        )
    }
}

@Composable
private fun DownloadRow(e: Downloads.Entry, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val menu = LocalVideoMenu.current
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.97f, onLongClick = { menu(e.item) }, onClick = onClick).padding(VideoRowInset).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(140.dp).aspectRatio(16f / 9f)) {
            Thumbnail(Downloads.withLocalThumb(e.item).thumbnail, Modifier.fillMaxSize(), radius = VideoRowCorner.value.toInt())
            DurationPill(e.item.durationSec, Modifier.align(Alignment.BottomEnd).padding(6.dp), small = true)
            if (e.ready) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (e.audioOnly) Icons.Rounded.Headphones else Icons.Rounded.DownloadDone, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.dl_ready), color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                ProgressLine(e.progress, Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.item.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.item.channel.isNotBlank()) Text(e.item.channel, color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            val failed = e.status == Downloads.Status.Failed
            Text(
                statusLine(e),
                color = if (failed) c.onErrorContainer else if (e.ready) c.onSurfaceVariant else c.onPrimaryContainer,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (failed) c.errorContainer else if (e.ready) c.surfaceContainerHigh else c.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}
