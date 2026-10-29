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
import androidx.compose.runtime.mutableStateMapOf
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

    private var videoId: String? = null
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
                .onFailure { liked[comment.id] = !now; onError(it.message ?: "Couldn't like that comment.") }
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
                    arrived(page.comments)
                    if (page.chapters.isNotEmpty()) onChapters?.invoke(id, page.chapters)
                }
                .onFailure { if (videoId == id) error = YouTube.friendlyError(it) }
            loading = false
        }
    }

    fun loadMore() {
        val id = videoId ?: return
        val page = next ?: return
        if (loading) return
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
                .onFailure { if (videoId == id) error = YouTube.friendlyError(it) }
            loading = false
        }
    }

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
                    author = profile?.handle ?: profile?.name ?: "You",
                    channelId = null,
                    name = profile?.name,
                    avatar = profile?.avatar,
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
            }.onFailure { postError = it.message ?: "Couldn't post your comment." }
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
            Text("Comments", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (state.total > 0) {
                Text(formatCount(state.total.toLong()), color = c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            state.disabled -> Text("Comments are turned off for this video.", color = c.onSurfaceVariant, fontSize = 13.5.sp)
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
            else -> Text(if (Account.signedIn) "No comments yet. Add the first one." else "No comments yet.", color = c.onSurfaceVariant, fontSize = 13.5.sp)
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
                    Text("Comments", fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp)
                    if (state.total > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(formatCount(state.total.toLong()), color = c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(44.dp).pressable { close() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Close comments", tint = c.onSurfaceVariant)
                    }
                }
            }
            val list = rememberLazyListState()
            val nearEnd by remember {
                derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 }
            }
            LaunchedEffect(nearEnd) { if (nearEnd) state.loadMore() }
            LazyColumn(state = list, modifier = Modifier.weight(1f).nestedScroll(listPull)) {
                items(state.comments, key = { it.id }) { comment -> CommentRow(comment, state) }
                if (state.loading || state.hasMore) item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) {
                        CircularProgressIndicator(color = c.primary, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                    }
                }
                if (!state.loading && state.comments.isEmpty()) item {
                    Text(
                        if (state.disabled) "Comments are turned off for this video." else "No comments yet.",
                        color = c.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(20.dp),
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
            if (Account.signedIn) Composer(state) else Text(
                "Sign in to comment.",
                color = c.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().background(c.surfaceContainer).padding(horizontal = 20.dp, vertical = 14.dp),
            )
            // Clear of the gesture bar when the keyboard isn't up.
            Spacer(Modifier.fillMaxWidth().background(if (Account.signedIn) c.surfaceContainer else c.surfaceContainer).windowInsetsBottomHeight(WindowInsets.navigationBars))
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
                Text("Replying to ${target.displayName()}", color = c.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(28.dp).pressable { state.replyingTo = null }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Cancel reply", tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp))
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
                    Text(if (state.replyingTo != null) "Add a reply…" else "Add a comment…", color = c.onSurfaceVariant, fontSize = 15.sp)
                }
                BasicTextField(
                    value = state.draft,
                    onValueChange = { state.draft = it },
                    textStyle = TextStyle(color = c.onSurface, fontSize = 15.sp),
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
                else Icon(Icons.AutoMirrored.Rounded.Send, "Post", tint = if (canSend) c.onPrimary else c.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
                if (comment.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = c.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(comment.displayName(), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                comment.ago?.let { Text(it, color = c.onSurfaceVariant, fontSize = 12.sp, maxLines = 1) }
            }
            Spacer(Modifier.height(3.dp))
            Text(comment.text, fontSize = 14.sp, lineHeight = 20.sp)
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
                        if (isLiked) "Unlike" else "Like",
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
                    Icon(Icons.Rounded.Favorite, "Hearted by creator", tint = c.primary, modifier = Modifier.size(14.dp))
                }
                if (Account.signedIn && !comment.id.startsWith("local-")) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "Reply",
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
                        if (open) "Hide replies" else "${comment.replyCount} ${if (comment.replyCount == 1) "reply" else "replies"}",
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
