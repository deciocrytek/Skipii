package com.decio.skipii

import android.util.Log

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import android.view.inputmethod.InputMethodManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Comments for the open video: first page, paging, per-comment replies, likes and posting. */
@Stable
class CommentsState(private val scope: CoroutineScope) {
    /** The watch page the first comments come from also carries the video's chapters. */
    var onChapters: ((videoId: String, chapters: List<Chapter>) -> Unit)? = null

    /** The video these comments are under. */
    var videoId: String? = null
        private set
    var comments by mutableStateOf<List<Comment>>(emptyList())
        private set
    var total by mutableStateOf(-1)
        private set
    var disabled by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var next: String? = null
    val hasMore get() = next != null
    /** YouTube's Top / Newest choice for these comments (GitHub #60); empty until the first page says it has one. */
    var sorts by mutableStateOf<List<CommentSort>>(emptyList())
        private set
    /** Newest first is shown (else Top). */
    var newest by mutableStateOf(false)
        private set
    /** A sort switch is loading: the list stays as it was, dimmed, until the reordered page lands. */
    var resorting by mutableStateOf(false)
        private set

    /** Replies already fetched, by comment id. */
    val replies = mutableStateMapOf<String, List<Comment>>()
    /** Comments the account has liked: from YouTube, plus taps this session. */
    val liked = mutableStateMapOf<String, Boolean>()

    /** Optimistic like toggle; reverts with a note if YouTube refuses. */
    fun toggleLike(comment: Comment, onError: (String) -> Unit) {
        val id = videoId ?: return
        val now = liked[comment.id] != true
        liked[comment.id] = now
        scope.launch {
            runCatching { CommentPosting.setLiked(id, comment.id, now) }
                .onFailure { liked[comment.id] = !now; onError(it.message ?: str(R.string.comments_error_like)) }
        }
    }
    val repliesLoading = mutableStateMapOf<String, Boolean>()

    private var job: Job? = null

    /** A new video opened: drop the old comments now; [load] follows once its data is in. */
    fun clearFor(id: String) {
        job?.cancel()
        videoId = id
        comments = emptyList(); total = -1; disabled = false; error = null; next = null
        replies.clear(); repliesLoading.clear(); replyingTo = null; draft = ""; postError = null
        sorts = emptyList(); newest = false; resorting = false
        loading = true
    }

    fun load(id: String) {
        videoId = id
        replyingTo = null
        draft = ""
        postError = null
        comments = emptyList()
        total = -1
        disabled = false
        error = null
        next = null
        replies.clear()
        repliesLoading.clear()
        liked.clear()
        sorts = emptyList()
        newest = false
        resorting = false
        loading = true
        job?.cancel()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        job = scope.launch {
            runCatching { CommentFeed.first(id) }
                .onSuccess { page ->
                    Log.i("Skipii", "comments for $id in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
                    if (videoId != id) return@onSuccess
                    comments = page.comments
                    total = page.total
                    disabled = page.disabled
                    next = page.next
                    sorts = page.sorts
                    newest = page.sorts.firstOrNull { it.selected }?.newest == true
                    arrived(page.comments)
                    if (page.chapters.isNotEmpty()) onChapters?.invoke(id, page.chapters)
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (videoId == id) error = YouTube.friendlyError(it)
                }
            loading = false
        }
    }

    fun loadMore() {
        val id = videoId ?: return
        val page = next ?: return
        // Not while a sort switch is landing: `next` is still the old order's and its page would join the new list.
        if (loading || resorting) return
        loading = true
        job = scope.launch {
            runCatching { CommentFeed.more(page) }
                .onSuccess { more ->
                    if (videoId != id) return@onSuccess
                    // Pages can overlap at the boundary; keep ids unique for the list keys.
                    val seen = comments.mapTo(HashSet()) { it.id }
                    comments = comments + more.comments.filter { seen.add(it.id) }
                    next = more.next
                    arrived(more.comments)
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (videoId == id) error = YouTube.friendlyError(it)
                }
            loading = false
        }
    }

    /**
     * Shows the comments newest first, or YouTube's Top order again (#60): the sort's token reloads the list from its
     * first page. The old list stays (dimmed) until the new one lands, so the panel never jumps to a spinner; a failure
     * keeps it and says so.
     */
    fun sortBy(newest: Boolean) {
        val id = videoId ?: return
        if (newest == this.newest || resorting) return
        val sort = sorts.firstOrNull { it.newest == newest } ?: return
        val was = this.newest
        this.newest = newest
        resorting = true
        job?.cancel()
        loading = false
        error = null
        job = scope.launch {
            runCatching { CommentFeed.more(sort.token) }
                .onSuccess { page ->
                    if (videoId != id) return@onSuccess
                    comments = page.comments
                    if (page.total > 0) total = page.total
                    next = page.next
                    replies.clear(); repliesLoading.clear()
                    if (page.sorts.isNotEmpty()) sorts = page.sorts
                    arrived(page.comments)
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (videoId == id) { this@CommentsState.newest = was; onSortFailed?.invoke(str(R.string.comments_sort_failed)) }
                }
            resorting = false
        }
    }

    /** Where a failed sort switch says so (the player's action message). */
    var onSortFailed: ((String) -> Unit)? = null

    fun loadReplies(comment: Comment) {
        val id = videoId ?: return
        val page = comment.repliesToken ?: return
        if (repliesLoading[comment.id] == true || replies.containsKey(comment.id)) return
        repliesLoading[comment.id] = true
        scope.launch {
            runCatching { CommentFeed.more(page) }
                .onSuccess { if (videoId == id) { replies[comment.id] = it.comments; arrived(it.comments) } }
            repliesLoading.remove(comment.id)
        }
    }

    /** New comments in: their liked state, and their authors' real names (looked up once each). */
    private fun arrived(page: List<Comment>) {
        page.forEach { if (it.liked) liked[it.id] = true }
        ChannelNames.resolve(scope, page.mapNotNullTo(LinkedHashSet()) { it.channelId })
    }

    fun hideReplies(comment: Comment) {
        replies.remove(comment.id)
    }

    // ── Posting (signed in) ──
    /** The comment being replied to, or null for a new top-level comment. */
    var replyingTo by mutableStateOf<Comment?>(null)
    var draft by mutableStateOf("")
    var posting by mutableStateOf(false)
        private set
    var postError by mutableStateOf<String?>(null)

    fun startReply(comment: Comment) {
        replyingTo = comment
        // Replies to a reply go to the same thread, addressed with an @mention (as on YouTube).
        if (comment.id.contains('.') && draft.isBlank()) draft = "${comment.author.trim()} "
    }

    fun post() {
        val id = videoId ?: return
        val text = draft.trim()
        if (text.isEmpty() || posting) return
        val target = replyingTo
        val threadId = target?.id?.substringBefore('.')
        posting = true
        postError = null
        scope.launch {
            runCatching {
                if (threadId == null) CommentPosting.comment(id, text) else CommentPosting.reply(id, threadId, text)
            }.onSuccess {
                val profile = Account.profile
                val mine = Comment(
                    id = "local-${System.nanoTime()}",
                    author = profile?.handle ?: profile?.name ?: str(R.string.comments_you),
                    channelId = null,
                    name = profile?.name,
                    avatar = profile?.avatar,
                    // English, as YouTube writes ages: shown through displayAge.
                    text = text, likes = 0, ago = "just now",
                    pinned = false, hearted = false, replyCount = 0, repliesToken = null,
                )
                if (threadId == null) {
                    comments = listOf(mine) + comments
                    if (total >= 0) total += 1
                } else {
                    replies[threadId] = replies[threadId].orEmpty() + mine
                }
                draft = ""
                replyingTo = null
            }.onFailure { postError = it.message ?: str(R.string.comments_error_post) }
            posting = false
        }
    }
}

/** The card under the video: count plus the top comment. Tap opens the full sheet. */
@Composable
fun CommentsCard(state: CommentsState, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val first = state.comments.firstOrNull()
    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .pressable(enabled = state.comments.isNotEmpty() || (Account.signedIn && !state.disabled && !state.loading), pressedScale = 0.98f, onClick = onOpen)
            .clip(shape)
            .background(c.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.comments_title), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (state.total > 0) {
                Text(formatCount(state.total.toLong()), color = c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.disabled -> Text(stringResource(R.string.comments_turned_off), color = c.onSurfaceVariant, fontSize = 13.5.sp)
            first != null -> Row {
                ChannelAvatar(first.avatar, first.author, size = 30)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = first.text,
                    color = c.onSurfaceVariant,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.loading -> CircularProgressIndicator(color = c.primary, strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
            state.error != null -> Text(state.error.orEmpty(), color = c.onSurfaceVariant, fontSize = 13.5.sp)
            else -> Text(stringResource(if (Account.signedIn) R.string.comments_none_add_first else R.string.comments_none), color = c.onSurfaceVariant, fontSize = 13.5.sp)
        }
    }
}

/**
 * Comments as a panel over the player's details, opening with its top just under the video
 * (the video keeps playing above it). The comment box is pinned to the panel's bottom at
 * every size. Drag the top handle up to cover the screen, down to shrink or close; a fling
 * decides on its own. The list scrolls first: only once it's at the top does a further pull
 * down move the panel the same way (Derek, 2026-10-10).
 */
@Composable
fun CommentsPanel(state: CommentsState, partialTop: Dp, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    PlayerPanel(
        partialTop = partialTop,
        onClose = onClose,
        closeLabel = stringResource(R.string.comments_close),
        title = {
            Text(
                stringResource(R.string.comments_title), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (state.total > 0) {
                Spacer(Modifier.width(8.dp))
                Text(formatCount(state.total.toLong()), color = c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
            }
            if (state.sorts.isNotEmpty() && !state.disabled) {
                Spacer(Modifier.width(12.dp))
                CommentSortToggle(state)
            }
        },
    ) { listModifier ->
        val dim by animateFloatAsState(if (state.resorting) 0.45f else 1f, tween(if (rememberReducedMotion()) 0 else 200), label = "resortDim")
        val list = rememberLazyListState()
        val nearEnd by remember {
            derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 }
        }
        LaunchedEffect(nearEnd) { if (nearEnd) state.loadMore() }
        // A new order starts at the top.
        LaunchedEffect(state.newest) { if (list.firstVisibleItemIndex > 0) list.scrollToItem(0) }
        LazyColumn(state = list, modifier = listModifier.graphicsLayer { alpha = dim }) {
            items(state.comments, key = { it.id }) { comment -> CommentRow(comment, state) }
            if (state.loading || state.hasMore) item {
                Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                    CircularProgressIndicator(color = c.primary, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                }
            }
            if (!state.loading && state.comments.isEmpty()) item {
                Text(
                    stringResource(if (state.disabled) R.string.comments_turned_off else R.string.comments_none),
                    color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(20.dp),
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (Account.signedIn) Composer(state) else Text(
            stringResource(R.string.comments_sign_in_to_comment),
            color = c.onSurfaceVariant, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(c.surfaceContainer).padding(horizontal = 20.dp, vertical = 14.dp),
        )
        // Clear of the gesture bar when the keyboard isn't up.
        Spacer(Modifier.fillMaxWidth().background(c.surfaceContainer).windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/**
 * The panel the comments and the transcript open in, over the player's details with its top just under
 * the video. Drag the top handle (and [title]) up to cover the screen, down to shrink or close; a fling
 * decides on its own. [content] gets the modifier for its list (it fills the room left and, once at its
 * top, a further pull down moves the panel); anything after the list stays pinned to the bottom.
 */
@Composable
fun PlayerPanel(
    partialTop: Dp,
    onClose: () -> Unit,
    closeLabel: String,
    title: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.(listModifier: Modifier) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val fullPx = with(density) { (maxHeight - statusTop - 8.dp).toPx() }
        val partialPx = with(density) { (maxHeight - partialTop).toPx() }.coerceIn(fullPx * 0.35f, fullPx)
        val height = remember { Animatable(partialPx) }
        // Slides up into place on open; slides back down to close.
        val slide = remember { Animatable(1f) }
        LaunchedEffect(Unit) { if (reduced) slide.snapTo(0f) else slide.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 550f)) }
        fun close() = scope.launch {
            if (!reduced) slide.animateTo(1f, tween(240, easing = Motion.Emphasized))
            onClose()
        }
        BackHandler { close() }
        fun dragBy(dy: Float): Float {
            val before = height.value
            val after = (before - dy).coerceIn(partialPx * 0.25f, fullPx)
            scope.launch { height.snapTo(after) }
            return before - after
        }
        fun settle(velocity: Float) {
            val h = height.value
            when {
                // Dragged down past 30%, flicked down once below the resting size, or flicked
                // hard from it: close.
                h < partialPx * 0.7f || velocity > 1200f && h < partialPx - 1f || velocity > 1800f && h <= partialPx + 1f -> close()
                velocity < -1200f -> scope.launch { height.animateTo(fullPx, spring(dampingRatio = 0.86f, stiffness = 550f)) }
                velocity > 1200f -> scope.launch { height.animateTo(partialPx, spring(dampingRatio = 0.86f, stiffness = 550f)) }
                else -> {
                    val target = if (h > (partialPx + fullPx) / 2f) fullPx else partialPx
                    scope.launch { height.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 550f)) }
                }
            }
        }
        // The list's leftover downward drag (it's already at the top) pulls the panel down;
        // reversing gives the height back, up to where the pull began, before the list scrolls.
        // Flings never move the panel, so a fast scroll up stops at the top.
        val listPull = remember(partialPx, fullPx) {
            object : NestedScrollConnection {
                var pulling = false
                var pullFrom = 0f

                fun pullBy(dy: Float): Float {
                    val before = height.value
                    val after = (before - dy).coerceIn(partialPx * 0.25f, pullFrom)
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { height.snapTo(after) }
                    return before - after
                }

                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (pulling && source == NestedScrollSource.UserInput && available.y < 0f) return Offset(0f, pullBy(available.y))
                    return Offset.Zero
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                    if (!pulling) { pulling = true; pullFrom = height.value }
                    return Offset(0f, pullBy(available.y))
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (!pulling) return Velocity.Zero
                    pulling = false
                    settle(available.y)
                    return available
                }
            }
        }
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { height.value.toDp() })
                .graphicsLayer { translationY = slide.value * size.height }
                .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(shape)
                .background(c.surfaceContainerLow)
                // Swallow taps so nothing underneath reacts through the panel.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .imePadding(),
        ) {
            // Handle + title: the drag area.
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        state = rememberDraggableState { dragBy(it) },
                        orientation = Orientation.Vertical,
                        onDragStopped = { v -> settle(v) },
                    ),
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.onSurfaceVariant.copy(alpha = 0.45f)))
                }
                Row(Modifier.padding(start = 20.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // All the room left of the close button is the title's (a title that shrinks, e.g. the comments' with
                    // their sort, gets it all, not half).
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { title() }
                    Box(Modifier.size(44.dp).pressable { close() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, closeLabel, tint = c.onSurfaceVariant)
                    }
                }
            }
            content(Modifier.weight(1f).nestedScroll(listPull))
        }
    }
}

/**
 * Wide windows (Rail 3): the comments flow in the player's own list under the details, as they read
 * on a page, instead of a card that opens a panel. The heading with the count, the comment box when
 * signed in, then every comment with its replies; the next page loads as the end comes into view.
 */
fun LazyListScope.inlineComments(state: CommentsState) {
    item(key = "commentsHead") {
        val c = MaterialTheme.colorScheme
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.comments_title), fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
            if (state.total > 0) {
                Spacer(Modifier.width(8.dp))
                Text(formatCount(state.total.toLong()), color = c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (state.sorts.isNotEmpty() && !state.disabled) {
                Spacer(Modifier.weight(1f))
                CommentSortToggle(state)
            }
        }
    }
    if (Account.signedIn && !state.disabled) item(key = "commentsComposer") {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(26.dp))) { Composer(state) }
    }
    items(state.comments, key = { "comment-" + it.id }) { comment -> CommentRow(comment, state) }
    when {
        state.loading || state.hasMore -> item(key = "commentsMore") {
            // Composed as it nears the screen: that's the cue for the next page.
            LaunchedEffect(state.comments.size) { state.loadMore() }
            Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
            }
        }
        state.comments.isEmpty() -> item(key = "commentsNone") {
            Text(
                state.error ?: stringResource(if (state.disabled) R.string.comments_turned_off else R.string.comments_none),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** Text field pinned to the bottom of the sheet; Send (or Enter) posts and dismisses the keyboard. */
@Composable
private fun Composer(state: CommentsState) {
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val view = LocalView.current
    val requester = remember { FocusRequester() }
    fun send() {
        focus.clearFocus(force = true)
        keyboard?.hide()
        view.postDelayed({
            view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        }, 120)
        state.post()
    }
    // Tapping Reply focuses the field.
    LaunchedEffect(state.replyingTo) { if (state.replyingTo != null) runCatching { requester.requestFocus() } }
    Column(Modifier.fillMaxWidth().background(c.surfaceContainer).padding(horizontal = 12.dp, vertical = 10.dp)) {
        state.replyingTo?.let { target ->
            Row(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.comments_replying_to, target.displayName()), color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(28.dp).pressable { state.replyingTo = null }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.comments_cancel_reply), tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
        state.postError?.let { Text(it, color = c.error, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(c.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (state.draft.isEmpty()) {
                    Text(stringResource(if (state.replyingTo != null) R.string.comments_add_reply else R.string.comments_add_comment), color = c.onSurfaceVariant, fontSize = 15.sp)
                }
                BasicTextField(
                    value = state.draft,
                    onValueChange = { state.draft = it },
                    textStyle = OwnDirectionText.merge(TextStyle(color = c.onSurface, fontSize = 15.sp)),
                    cursorBrush = SolidColor(c.primary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Sentences),
                    keyboardActions = KeyboardActions(onSend = { send() }, onDone = { send() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester).onPreviewKeyEvent { e ->
                        if (e.key == Key.Enter || e.key == Key.NumPadEnter) {
                            if (e.type == KeyEventType.KeyDown) send()
                            true
                        } else false
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            val canSend = state.draft.isNotBlank() && !state.posting
            Box(
                Modifier.size(46.dp).pressable(enabled = canSend) { send() }.clip(CircleShape)
                    .background(if (canSend) c.primary else c.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (state.posting) CircularProgressIndicator(color = c.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.comments_post), tint = if (canSend) c.onPrimary else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CommentRow(comment: Comment, state: CommentsState, isReply: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = if (isReply) 62.dp else 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp)) {
        ChannelAvatar(comment.avatar, comment.author, size = if (isReply) 28 else 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (comment.pinned) Icon(Icons.Rounded.PushPin, stringResource(R.string.comments_pinned), tint = c.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(comment.displayName(), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                displayAge(comment.ago)?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1) }
            }
            Spacer(Modifier.height(3.dp))
            // Timestamps seek, links open (#66), where the open player provides LocalLinkOpener.
            val onLink = LocalLinkOpener.current
            val linked = remember(comment.text, state.videoId, onLink != null) {
                if (onLink == null) Description(comment.text) else commentLinks(comment.text, state.videoId)
            }
            // Double-tap the text to like (signed in, GitHub #54); never unlikes, as Shorts. A link's own tap
            // consumes its lift, so a double tap on a link doesn't count.
            val canDoubleTap = Account.signedIn && !comment.id.startsWith("local-")
            val view = LocalView.current
            Box(
                if (!canDoubleTap) Modifier else Modifier.pointerInput(comment.id) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
                            ?: return@awaitEachGesture
                        if ((second.position - up.position).getDistance() > viewConfiguration.touchSlop * 3) return@awaitEachGesture
                        if (waitForUpOrCancellation() == null) return@awaitEachGesture
                        if (state.liked[comment.id] != true) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                            state.toggleLike(comment) { state.postError = it }
                        }
                    }
                },
            ) { LinkedText(linked, onLink) }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Like (signed in): pops like the video's like, and the count follows.
                val isLiked = state.liked[comment.id] == true
                val canLike = Account.signedIn && !comment.id.startsWith("local-")
                val likeTint = if (isLiked) c.primary else c.onSurfaceVariant
                Row(
                    Modifier
                        .pressable(enabled = canLike) { state.toggleLike(comment) { state.postError = it } }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FlourishIcon(
                        if (isLiked) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp,
                        stringResource(if (isLiked) R.string.comments_unlike else R.string.comments_like),
                        active = isLiked, kind = Flourish.Pop, tint = likeTint, size = 16.dp,
                    )
                    val count = comment.likes + if (isLiked) 1 else 0
                    if (count > 0) {
                        Spacer(Modifier.width(5.dp))
                        Text(formatCount(count.toLong()), color = likeTint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (comment.hearted) {
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Rounded.Favorite, stringResource(R.string.comments_hearted), tint = c.primary, modifier = Modifier.size(14.dp))
                }
                if (Account.signedIn && !comment.id.startsWith("local-")) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(R.string.comments_reply),
                        color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.pressable { state.startReply(comment) }.padding(vertical = 2.dp),
                    )
                }
            }
            if (!isReply && comment.replyCount > 0 && comment.repliesToken != null) {
                val open = state.replies.containsKey(comment.id)
                val busy = state.repliesLoading[comment.id] == true
                Row(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .pressable { if (open) state.hideReplies(comment) else state.loadReplies(comment) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (open) stringResource(R.string.comments_hide_replies)
                        else plural(R.plurals.comments_replies, comment.replyCount.toLong(), comment.replyCount.toString()),
                        color = c.primary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                    )
                    if (busy) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(color = c.primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
    if (!isReply) {
        state.replies[comment.id]?.forEach { reply -> CommentRow(reply, state, isReply = true) }
    }
}

/**
 * Top | Newest (GitHub #60; Neon's spec): a 34 dp capsule on the container tone with a primary pill that springs
 * to the chosen word. The comments reload in that order ([CommentsState.sortBy]).
 */
@Composable
private fun CommentSortToggle(state: CommentsState) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    val spots = remember { mutableStateMapOf<Boolean, Pair<Float, Float>>() }
    val left = remember { Animatable(0f) }
    val width = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = spots[state.newest]
    LaunchedEffect(target) {
        val (l, w) = target ?: return@LaunchedEffect
        if (!placed || reduced) { left.snapTo(l); width.snapTo(w); placed = true; return@LaunchedEffect }
        val spec = spring<Float>(dampingRatio = 0.72f, stiffness = 520f)
        launch { left.animateTo(l, spec) }
        width.animateTo(w, spec)
    }
    Row(
        Modifier
            .height(34.dp)
            .clip(CircleShape)
            .background(c.surfaceContainerHigh)
            .drawBehind {
                if (!placed) return@drawBehind
                val inset = 3.dp.toPx()
                // Words sit inside the 3 dp padding drawn here outside it.
                drawRoundRect(c.primary, topLeft = Offset(left.value + inset, inset), size = Size(width.value, size.height - 2 * inset), cornerRadius = CornerRadius(size.height / 2))
            }
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(false, true).forEach { newest ->
            val on = state.newest == newest
            val ink by androidx.compose.animation.animateColorAsState(if (on) c.onPrimary else c.onSurfaceVariant, tween(if (reduced) 0 else 180, easing = Motion.Emphasized), label = "sortInk")
            Box(
                Modifier
                    .onPlaced { spots[newest] = it.positionInParent().x to it.size.width.toFloat() }
                    .height(28.dp)
                    .pressable { state.sortBy(newest) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(if (newest) R.string.comments_sort_newest else R.string.comments_sort_top),
                    color = ink, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
    }
}
