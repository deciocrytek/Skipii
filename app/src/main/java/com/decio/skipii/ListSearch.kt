package com.decio.skipii

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import java.util.Locale

/*
 * Search inside Watch later, Liked videos and History (2026-11-20, Derek picked variation A "Field under the title":
 * the field is the list's first item and scrolls away with it). Watch later / Liked are searched on the phone over
 * EVERY video (FeedState.loadAll pages to the end; both lists were paged to their ends on Derek's account). History
 * uses YouTube's own history search, which only covers recent history, and says so.
 */

private val Marks = Regex("\\p{Mn}+")
private val Spaces = Regex("\\s+")

/** Lower case, accents dropped: "Café" matches "cafe". */
internal fun foldForSearch(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Marks, "").lowercase(Locale.ROOT)

/** The query's words, folded; empty = not searching. */
internal fun queryWords(query: String): List<String> =
    foldForSearch(query).split(Spaces).filter { it.isNotEmpty() }

/** Every word appears in the video's title or channel name. */
internal fun matchesQuery(video: VideoItem, words: List<String>): Boolean =
    words.isEmpty() || foldForSearch(video.title + " " + video.channel).let { hay -> words.all { it in hay } }

/**
 * Each video's title + channel folded ONCE: re-folding 3,671 liked videos on every keystroke (and every page
 * landing) kept the main thread busy enough that typed letters were dropped ("apple" came out "appl").
 */
class ListSearchIndex {
    private val folded = HashMap<String, String>()

    fun filter(videos: List<VideoItem>, words: List<String>): List<VideoItem> =
        if (words.isEmpty()) videos
        else videos.filter { v ->
            val hay = folded.getOrPut(v.id) { foldForSearch(v.title + " " + v.channel) }
            words.all { it in hay }
        }
}

/**
 * The search field over a list (Settings' search field look): Search on the keyboard closes it (rule 4). It keeps its
 * own [TextFieldState] (the IME edits that buffer directly) and reports changes: a String-valued field dropped letters
 * typed in a burst while the list was busy (pages landing, filtering), "iphone" came out "iphon".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListSearchField(query: String, onQuery: (String) -> Unit, placeholder: String, onSubmit: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val text = rememberTextFieldState(query)
    val report by rememberUpdatedState(onQuery)
    // One way only: [query] is just the starting text (the state is saved with the page). Pushing [query] back into
    // the buffer would race the IME — a report of "a" arriving after "ab" was typed would undo the "b".
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { report(it) } }
    fun done() {
        onSubmit()
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        view.postDelayed({ view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0) }, 120)
    }
    Row(
        Modifier
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.surfaceContainerHighest)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val empty = text.text.isEmpty()
        Icon(Icons.Rounded.Search, null, tint = if (empty) c.onSurfaceVariant else c.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (empty) {
                Text(placeholder, color = c.onSurfaceVariant, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                state = text,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = LocalTextStyle.current.merge(TextStyle(color = c.onSurface, fontSize = 16.sp)),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                onKeyboardAction = { done() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!empty) {
            Box(Modifier.size(40.dp).pressable { text.clearText() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, stringResource(R.string.ui_clear_search), tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** The line under the field while searching ("3 of 1,667", "YouTube searches your recent history"). */
@Composable
fun ListSearchNote(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp),
    )
}

/** Nothing matched (the designed empty state, rule 5). */
@Composable
fun ListSearchEmpty(body: String) {
    EmptyState(Icons.Rounded.SearchOff, stringResource(R.string.ui_search_no_matches), body)
}
