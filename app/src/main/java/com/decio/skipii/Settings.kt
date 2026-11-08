package com.decio.skipii

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.Normalizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.BugReport
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Settings (Derek, 2026-11-02: example 4 "Big title that folds" in docs/design/settings-merged.html, Ledgii's
 * settings page merged with Android's own). A page of its own over the app, opened from the header avatar.
 * The large title and the search field fold into a small bar as the page scrolls (search becomes an icon
 * there). Up front: the account card and four quick toggles; then every category as a tile with a summary
 * of what's set, tiles in a group joined into one block. Search finds any setting in any category; a
 * result opens its category scrolled to that setting, which lights up. A category is one level deep and
 * slides in Ledgii's way (shared axis X).
 */
enum class SettingsPage(@StringRes val titleRes: Int, val icon: ImageVector) {
    Playback(R.string.settings_playback, Icons.Rounded.PlayCircle),
    Captions(R.string.captions_title, Icons.Rounded.ClosedCaption),
    SponsorBlock(R.string.settings_sponsorblock, Icons.Rounded.FastForward),
    Shorts(R.string.ui_shorts, Icons.Rounded.SlowMotionVideo),
    Appearance(R.string.ui_appearance, Icons.Rounded.Palette),
    Language(R.string.language_title, Icons.Rounded.Translate),
    Notifications(R.string.settings_notifications, Icons.Rounded.Notifications),
    About(R.string.settings_about, Icons.Rounded.Info),
    Account(R.string.settings_account, Icons.Rounded.Person),
}

private val BarHeight = 64.dp
/** Scrolled this far, the big title has folded into the bar. */
private val FoldDistance = 70.dp
private val OuterCorner = 21.dp
private val InnerCorner = 7.dp
private val JoinGap = 3.dp
private const val SourceUrl = "https://github.com/deciocrytek/Skipii"

/**
 * The whole page. [active] is false while it slides away (its Back stops catching). Back goes up one
 * level: a category → the front page, a search → cleared, the front page → closed.
 *
 * [wide] (a window 600 dp or wider; large screens, "Rail 3" in docs/design/large-screens-rail.html): list-detail.
 * The front page stays on the left with the open category's tile lit; the category is a panel on the right
 * with its own title and scroll. Both are always in view, so Back only clears a search, then closes. All
 * state lives above the switch, so a window that narrows (rotation, a fold) carries on as the phone page on
 * the same category, and going wide again keeps it.
 */
@Composable
fun SettingsScreen(
    player: PlayerState,
    active: Boolean,
    onClose: () -> Unit,
    onSignIn: () -> Unit,
    onSignedOut: () -> Unit,
    wide: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    val reduced = rememberReducedMotion()
    // Saveable: picking a language rebuilds the window, which reopens here on Language.
    // Opened wide, the panel's first category counts as open, so a window that narrows stays on it.
    var page by rememberSaveable { mutableStateOf(if (wide) SettingsPage.Playback else null) }
    /** The category opened last this session: what the wide page's panel shows while [page] is null (back on the phone's front page). */
    var last by rememberSaveable { mutableStateOf(SettingsPage.Playback) }
    var query by rememberSaveable { mutableStateOf("") }
    /** The setting a search result points at, lit once its category opens. */
    var target by remember { mutableStateOf<String?>(null) }
    /** Counts search picks, so a result in the category already open (wide) still scrolls to its row. */
    var findSeq by remember { mutableIntStateOf(0) }
    val indexScroll = rememberScrollState()
    // Moodii's entrance: flips true a frame after Settings opens; the front page rises in once per opening.
    val entered by rememberEntered()
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val panelShift = with(LocalDensity.current) { PanelShift.roundToPx() }

    fun open(to: SettingsPage, key: String?) {
        target = key
        page = to
        last = to
        if (key != null) findSeq++
    }

    val close = { focusManager.clearFocus(force = true); onClose() }

    BackHandler(enabled = active) {
        when {
            !wide && page != null -> { page = null; target = null }
            query.isNotEmpty() -> query = ""
            else -> close()
        }
    }

    @Composable
    fun Front(selected: SettingsPage?) = FrontPane(
        player = player,
        query = query,
        onQuery = { query = it },
        scroll = indexScroll,
        entered = entered,
        searchFocus = searchFocus,
        selected = selected,
        onOpen = { open(it, null) },
        onPick = { hit ->
            focusManager.clearFocus(force = true)
            open(hit.page, hit.key)
        },
        onClose = close,
    )

    @Composable
    fun Category(shown: SettingsPage, inPanel: Boolean) {
        val scroll = rememberScrollState()
        val find = remember(shown) { Find(target) }
        // A plain field the reach effect reads: the row the latest search pick points at (null after a tile tap).
        find.key = target
        FoldingPage(
            title = stringResource(shown.titleRes),
            summary = summaryOf(shown),
            scroll = scroll,
            find = find,
            findSeq = findSeq,
            inPanel = inPanel,
            onBack = { page = null; target = null },
        ) {
            CompositionLocalProvider(LocalFind provides find) {
                when (shown) {
                    SettingsPage.Playback -> PlaybackPage(player)
                    SettingsPage.Captions -> CaptionsPage()
                    SettingsPage.SponsorBlock -> SponsorBlockPage()
                    SettingsPage.Shorts -> ShortsPage()
                    SettingsPage.Appearance -> AppearancePage()
                    SettingsPage.Language -> LanguagePage()
                    SettingsPage.Notifications -> NotificationsPage(player)
                    SettingsPage.About -> AboutPage(player)
                    SettingsPage.Account -> AccountPage(onSignIn, onSignedOut)
                }
            }
        }
    }

    if (wide) {
        val detail = page ?: last
        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(c.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            val listWidth = (maxWidth * 0.45f).coerceIn(300.dp, 400.dp)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth).fillMaxHeight()) { Front(selected = detail) }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = statusTop + 8.dp, end = 12.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(PanelCorner))
                        .background(c.surfaceContainerLow),
                ) {
                    AnimatedContent(
                        targetState = detail,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            // Shared axis Y inside the panel: the next category comes in from its tile's side.
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                            else (slideInVertically(tween(440, easing = SettingsEase)) { dir * panelShift } + fadeIn(tween(350, delayMillis = 60))) togetherWith
                                (slideOutVertically(tween(260)) { -dir * panelShift * 3 / 4 } + fadeOut(tween(160)))
                        },
                        label = "settingsPanel",
                    ) { shown -> Category(shown, inPanel = true) }
                }
            }
        }
        return
    }

    AnimatedContent(
        targetState = page,
        modifier = Modifier.fillMaxSize().background(c.surface),
        transitionSpec = {
            // Ledgii's shared axis X: in from 1/8 of the width while fading, out a little the other way.
            val dir = if (targetState != null) 1 else -1
            if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
            else (slideInHorizontally(tween(440, easing = SettingsEase)) { dir * it / 8 } + fadeIn(tween(350))) togetherWith
                (slideOutHorizontally(tween(260)) { -dir * it / 10 } + fadeOut(tween(220)))
        },
        label = "settingsPage",
    ) { shown ->
        if (shown == null) Front(selected = null) else Category(shown, inPanel = false)
    }
}

/** The front page: the big title, search, then the account card, quick toggles and tiles (or the search results). */
@Composable
private fun FrontPane(
    player: PlayerState,
    query: String,
    onQuery: (String) -> Unit,
    scroll: ScrollState,
    entered: Boolean,
    searchFocus: FocusRequester,
    selected: SettingsPage?,
    onOpen: (SettingsPage) -> Unit,
    onPick: (Hit) -> Unit,
    onClose: () -> Unit,
) {
    FoldingPage(
        title = stringResource(R.string.settings_title),
        summary = null,
        scroll = scroll,
        find = null,
        titleModifier = Modifier.riseIn(entered, 0),
        onBack = onClose,
        onSearch = {
            scroll.animateScrollTo(0)
            searchFocus.requestFocus()
        },
    ) {
        Box(Modifier.riseIn(entered, 1)) { SearchField(query, onQuery, searchFocus) }
        if (query.isBlank()) FrontPage(player, entered, selected, onOpen) else Results(query, onPick)
    }
}

/** The wide page's category panel: its corners and how far a category travels as it comes in. */
private val PanelCorner = 28.dp
private val PanelShift = 40.dp

/** Ledgii's settings curve (fast out, long soft landing). */
private val SettingsEase = CubicBezierEasing(0.2f, 0.85f, 0.22f, 1f)

// ── The page frame: big title that folds into the bar ─────────────────────────

@Composable
private fun FoldingPage(
    title: String,
    summary: String?,
    scroll: ScrollState,
    find: Find?,
    onBack: () -> Unit,
    titleModifier: Modifier = Modifier,
    onSearch: (suspend () -> Unit)? = null,
    findSeq: Int = 0,
    /** The wide page's category panel: already clear of the status bar, no back arrow, the panel's tone. */
    inPanel: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val foldPx = with(density) { FoldDistance.toPx() }
    // Read in layer lambdas only, so scrolling never recomposes the page.
    val fold = { (scroll.value / foldPx).coerceIn(0f, 1f) }
    val folded by remember { derivedStateOf { fold() > 0.6f } }
    val statusTop = if (inPanel) 0.dp else WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val surface = if (inPanel) c.surfaceContainerLow else c.surface
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (find != null) {
        find.scroll = scroll
        find.topPx = with(density) { (statusTop + BarHeight + 48.dp).toPx() }
        LaunchedEffect(find, findSeq) { find.reach() }
    }
    Box(Modifier.fillMaxSize().background(surface)) {
        Column(
            Modifier
                .fillMaxSize()
                // Before the scroll, so a focused field scrolls clear of the keyboard.
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .verticalScroll(scroll)
                .onGloballyPositioned { find?.content = it }
                .padding(horizontal = if (inPanel) 24.dp else 20.dp),
        ) {
            // In the panel the title sits near its top; the bar only appears (fades in) as the title folds into it.
            Spacer(Modifier.height(if (inPanel) 16.dp else statusTop + BarHeight))
            Text(
                title,
                modifier = titleModifier
                    .padding(start = 4.dp, top = 6.dp)
                    .graphicsLayer {
                        val f = fold()
                        alpha = 1f - f
                        val s = 1f - 0.12f * f
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 1f)
                    },
                fontFamily = RoundedHeavy,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 38.sp,
                lineHeight = 42.sp,
                letterSpacing = (-1.2).sp,
            )
            if (summary != null) {
                Text(
                    summary,
                    color = c.onSurfaceVariant,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp),
                )
            }
            content()
            Spacer(Modifier.height(36.dp))
        }
        // Under the folded bar the page fades out instead of cutting off (the tabs' top vignette).
        val vignette = remember(surface) { vignetteBrush(surface, opaqueAtTop = true) }
        Box(
            Modifier
                .padding(top = statusTop + BarHeight)
                .fillMaxWidth()
                .height(28.dp)
                .graphicsLayer { alpha = fold() }
                .drawBehind { drawRect(vignette) },
        )
        // The bar the title folds into (opaque: the page scrolls under it).
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(surface, alpha = if (inPanel) fold() else 1f) }
                .padding(top = statusTop)
                .height(BarHeight)
                .padding(start = if (inPanel) 22.dp else 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!inPanel) {
                Box(Modifier.size(48.dp).pressable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), tint = c.onSurface)
                }
            }
            Text(
                title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (inPanel) 0.dp else 6.dp)
                    .graphicsLayer {
                        val f = fold()
                        alpha = f
                        translationY = (1f - f) * 10.dp.toPx()
                    },
                fontFamily = RoundedHeavy,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onSearch != null) {
                Box(
                    Modifier
                        .size(48.dp)
                        .graphicsLayer { alpha = fold() }
                        .pressable(enabled = folded) { scope.launch { onSearch() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Search, stringResource(R.string.settings_search), tint = c.onSurface)
                }
            }
        }
    }
}

// ── Front page ────────────────────────────────────────────────────────────────

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, focus: FocusRequester) {
    val c = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    // Search dismisses the keyboard (rule 4; the window-level hide comes ~120 ms later or the IME undoes it).
    fun done() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        view.postDelayed({ view.context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0) }, 120)
    }
    Row(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(c.surfaceContainerHighest)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.settings_search), color = c.onSurfaceVariant, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(TextStyle(color = c.onSurface, fontSize = 16.sp)),
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { done() }, onDone = { done() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(40.dp).pressable { onQuery("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, stringResource(R.string.ui_clear_search), tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun FrontPage(player: PlayerState, entered: Boolean, selected: SettingsPage?, onOpen: (SettingsPage) -> Unit) {
    Box(Modifier.riseIn(entered, 2)) { AccountCard { onOpen(SettingsPage.Account) } }
    Box(Modifier.riseIn(entered, 3)) { QuickToggles(player) }
    Column(Modifier.riseIn(entered, 4)) {
        GroupLabel(stringResource(R.string.settings_group_watching))
        CategoryTiles(listOf(SettingsPage.Playback, SettingsPage.Captions, SettingsPage.SponsorBlock, SettingsPage.Shorts), selected, onOpen)
    }
    Column(Modifier.riseIn(entered, 5)) {
        GroupLabel(stringResource(R.string.settings_group_look))
        CategoryTiles(listOf(SettingsPage.Appearance, SettingsPage.Language), selected, onOpen)
    }
    Column(Modifier.padding(top = 24.dp).riseIn(entered, 6)) {
        CategoryTiles(listOf(SettingsPage.Notifications, SettingsPage.About), selected, onOpen)
    }
    DeveloperCard(player, Modifier.padding(top = 24.dp).riseIn(entered, 7))
}

/** Ledgii's account card: primary container, the avatar, who's signed in, a chevron into Account. */
@Composable
private fun AccountCard(onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val profile = Account.profile
    Row(
        Modifier
            .padding(top = 14.dp)
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .clip(RoundedCornerShape(OuterCorner))
            .background(c.primaryContainer)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (profile != null) {
            ChannelAvatar(profile.avatar, profile.name, size = 56)
        } else {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.surfaceContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, null, tint = c.primary, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile?.name ?: stringResource(R.string.ui_sign_in),
                color = c.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                if (profile != null) stringResource(R.string.settings_full_sync) else stringResource(R.string.ui_account_pitch_title),
                color = c.onPrimaryContainer.copy(alpha = 0.75f), fontSize = 12.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

/** The most used switches, one tap from the front page (like Quick Settings). */
@Composable
private fun QuickToggles(player: PlayerState) {
    val context = LocalContext.current
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickToggle(Icons.Rounded.Headphones, stringResource(R.string.bar_audio_only), AudioOnly.enabled, Modifier.weight(1f)) {
                player.setAudioOnly(!AudioOnly.enabled)
            }
            QuickToggle(Icons.Rounded.FastForward, stringResource(R.string.settings_sponsorblock), SponsorBlock.enabled, Modifier.weight(1f)) {
                SponsorBlock.updateEnabled(!SponsorBlock.enabled)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickToggle(Icons.Rounded.SlowMotionVideo, stringResource(R.string.ui_shorts), ShortsSetting.enabled, Modifier.weight(1f)) {
                ShortsSetting.set(context, !ShortsSetting.enabled)
            }
            QuickToggle(Icons.Rounded.Speed, stringResource(R.string.settings_speed_keep), PlaybackSpeed.remember, Modifier.weight(1f)) {
                player.toggleRememberSpeed()
            }
        }
    }
}

@Composable
private fun QuickToggle(icon: ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val background by animateColorAsState(if (on) c.primary else c.surfaceContainerHigh, tween(260), label = "quickBg")
    val content by animateColorAsState(if (on) c.onPrimary else c.onSurface, tween(260), label = "quickFg")
    Row(
        modifier
            .pressable(pressedScale = 0.95f, onClick = onClick)
            .clip(RoundedCornerShape(22.dp))
            .drawBehind { drawRect(background) }
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = content, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = content, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(if (on) R.string.settings_on else R.string.settings_off),
                color = content.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1,
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 9.dp),
    )
}

/** Rows that touch: 3 dp apart, small inner corners, the block's outer corners round. */
@Composable
private fun Joined(count: Int, item: @Composable (index: Int, shape: Shape) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(JoinGap)) {
        for (i in 0 until count) item(i, joinedShape(i, count))
    }
}

private fun joinedShape(index: Int, count: Int): Shape {
    val top = if (index == 0) OuterCorner else InnerCorner
    val bottom = if (index == count - 1) OuterCorner else InnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun CategoryTiles(pages: List<SettingsPage>, selected: SettingsPage?, onOpen: (SettingsPage) -> Unit) {
    Joined(pages.size) { i, shape ->
        val page = pages[i]
        Tile(page.icon, stringResource(page.titleRes), summaryOf(page), shape, selected = page == selected) { onOpen(page) }
    }
}

/**
 * Ledgii's tile: an icon square, the title and a one-line summary, a chevron. [selected] (the wide page's open
 * category) turns it primary container, both tones opaque.
 */
@Composable
private fun Tile(
    icon: ImageVector,
    title: AnnotatedString,
    line: AnnotatedString?,
    shape: Shape,
    crumb: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    // A 0…1 blend rather than animated colours, so a theme change still lands at once.
    val lit by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "tileLit")
    val background = lerp(c.surfaceContainer, c.primaryContainer, lit)
    val content = lerp(LocalContentColor.current, c.onPrimaryContainer, lit)
    val muted = lerp(c.onSurfaceVariant, c.onPrimaryContainer.copy(alpha = 0.8f), lit)
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .clip(shape)
            .drawBehind { drawRect(background) }
            .heightIn(min = 78.dp)
            .padding(horizontal = 17.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconSquare(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (crumb != null) {
                Text(crumb, color = c.primary, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
            }
            Text(title, color = content, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (line != null) {
                Spacer(Modifier.height(4.dp))
                Text(line, color = muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Tile(icon: ImageVector, title: String, line: String?, shape: Shape, selected: Boolean = false, onClick: () -> Unit) =
    Tile(icon, AnnotatedString(title), line?.let(::AnnotatedString), shape, null, selected, onClick)

@Composable
private fun IconSquare(icon: ImageVector) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(c.secondaryContainer), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.primary, modifier = Modifier.size(22.dp))
    }
}

/**
 * Moodii's entrance trigger: flips true one frame after first composition. With animations off
 * it starts true, so nothing rises.
 */
@Composable
private fun rememberEntered(): androidx.compose.runtime.State<Boolean> {
    val reduced = rememberReducedMotion()
    val entered = remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) { entered.value = true }
    return entered
}

/** Moodii's riseIn: fades in and rises 26 dp into place, [order] × 70 ms after the page appears. */
private fun Modifier.riseIn(entered: Boolean, order: Int): Modifier = composed {
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(620, delayMillis = 70 * order, easing = MoodiiDecelerate),
        label = "riseIn",
    )
    graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 26.dp.toPx()
    }
}

/** Moodii's EmphasizedDecelerate. */
private val MoodiiDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

// ── Developer card (Moodii's, last on the front page) ─────────────────────────

private const val InstagramUrl = "https://www.instagram.com/y7esdii?igsh=MWdydmU1a216NDlydA=="
private const val ThreadsUrl = "https://www.threads.com/@y7esdii"
private const val XUrl = "https://x.com/y7esdii"
private const val DeveloperEmail = "decioarruda7c@gmail.com"
/** A filterable keyword in the subject, keyed to this app. */
private const val EmailSubject = "Skipii feedback"

@Composable
private fun DeveloperCard(player: PlayerState, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val onMessage: (String) -> Unit = { player.actionMessage = it }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(OuterCorner))
            .background(c.surfaceContainer)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.settings_made_by, "@y7esdii"), fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.settings_say_hi), color = c.onSurfaceVariant, fontSize = 12.5.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconDisc(rememberBrandIcon("Instagram", InstagramPath), "Instagram") { context.openOrSay(Intent(Intent.ACTION_VIEW, Uri.parse(InstagramUrl)), onMessage) }
            IconDisc(rememberBrandIcon("Threads", ThreadsPath), "Threads") { context.openOrSay(Intent(Intent.ACTION_VIEW, Uri.parse(ThreadsUrl)), onMessage) }
            IconDisc(rememberBrandIcon("X", XPath), "X") { context.openOrSay(Intent(Intent.ACTION_VIEW, Uri.parse(XUrl)), onMessage) }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(pressedScale = 0.97f) {
                    // SENDTO + mailto: only mail apps answer (SEND would offer every messaging app).
                    context.openOrSay(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DeveloperEmail")).putExtra(Intent.EXTRA_SUBJECT, EmailSubject),
                        onMessage,
                    )
                }
                .clip(CircleShape)
                .background(c.secondaryContainer)
                .height(48.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.MailOutline, null, tint = c.onSecondaryContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_email_developer), color = c.onSecondaryContainer, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
        }
    }
}

@Composable
private fun IconDisc(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(44.dp)
            .pressable(pressedScale = 0.88f, onClick = onClick)
            .clip(CircleShape)
            .background(c.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/** Opens [intent]; with no app on the phone to take it, says so instead. */
private fun android.content.Context.openOrSay(intent: Intent, onMessage: (String) -> Unit) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        onMessage(str(R.string.settings_no_app))
    }
}

/**
 * Brand marks as vectors (Moodii's BrandIcons). Even-odd fill: the Instagram lens, the X's notches and the
 * Threads loop are counters that fill solid under the default non-zero rule.
 */
@Composable
private fun rememberBrandIcon(name: String, path: String): ImageVector = remember(name) {
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        .build()
}

// ── Summaries (each tile's line, and under a category's title) ────────────────

@Composable
private fun summaryOf(page: SettingsPage): String = when (page) {
    SettingsPage.Playback -> listOfNotNull(
        // A TV has neither (TvPlayer): its controls are the remote's.
        if (LocalWindowLayout.current.tv) null
        else stringResource(if (PlayerControls.inBar) R.string.ui_player_controls_pill else R.string.ui_player_controls_on_video),
        if (PlaybackSpeed.remember) stringResource(R.string.settings_speed_kept_summary, PlaybackSpeed.label(PlaybackSpeed.saved))
        else stringResource(R.string.settings_speed_per_video),
        if (SeekStep.seconds != 10) stringResource(R.string.settings_seek_step_summary, SeekStep.seconds.toString()) else null,
        if (FullscreenZoom.fill && !LocalWindowLayout.current.tv) stringResource(R.string.settings_zoom_fill_summary) else null,
        if (AudioOnly.enabled) stringResource(R.string.bar_audio_only) else null,
    ).joinDots()
    SettingsPage.Captions -> listOf(captionShowLabel(CaptionSettings.show), stringResource(CaptionSettings.look.labelRes)).joinDots()
    SettingsPage.SponsorBlock ->
        if (!SponsorBlock.enabled) stringResource(R.string.settings_off)
        else stringResource(
            R.string.settings_sb_summary,
            SponsorBlock.Category.entries.count { SponsorBlock.actionFor(it) == SponsorBlock.Action.Auto }.toString(),
            SponsorBlock.Category.entries.count { SponsorBlock.actionFor(it) == SponsorBlock.Action.Button }.toString(),
        )
    SettingsPage.Shorts -> stringResource(if (ShortsSetting.enabled) R.string.ui_shorts_on_line else R.string.settings_off)
    SettingsPage.Appearance -> listOfNotNull(
        themeName(),
        modeLabel(ThemeSettings.mode),
        if (ThemeSettings.amoled && ThemeSettings.mode != "light") "AMOLED" else null,
    ).joinDots()
    SettingsPage.Language -> AppLanguage.choices.firstOrNull { it.tag == AppLanguage.pickedChoice }?.name ?: phoneLanguageLabel()
    SettingsPage.Notifications -> Bells.on.size.let { n ->
        if (n == 0) stringResource(R.string.settings_bells_none) else pluralStringResource(R.plurals.settings_bells_count, n, n.toString())
    }
    SettingsPage.About -> listOfNotNull(
        stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
        AppUpdate.available?.let { stringResource(R.string.settings_update_available, it.version) },
        if (CrashLog.unshared) stringResource(R.string.settings_crash_tile) else null,
    ).joinDots()
    SettingsPage.Account -> Account.profile?.let { p -> listOfNotNull(p.name, p.handle).joinDots() } ?: stringResource(R.string.ui_account_pitch_title)
}

@Composable
private fun captionShowLabel(show: CaptionSettings.Show): String = when (show) {
    CaptionSettings.Show.Never -> stringResource(R.string.captions_show_never)
    CaptionSettings.Show.Always -> stringResource(R.string.captions_show_always)
    CaptionSettings.Show.Foreign -> stringResource(R.string.captions_show_foreign, CaptionNames.language(CaptionSettings.appLanguage))
}

@Composable
private fun themeName(): String {
    val seed = ThemeSettings.seed ?: return stringResource(R.string.ui_theme_wallpaper)
    return SKIPII_THEMES.firstOrNull { it.hex.equals(seed, ignoreCase = true) }?.name ?: seed
}

@Composable
private fun modeLabel(mode: String): String = when (mode) {
    "light" -> stringResource(R.string.ui_mode_light)
    "dark" -> stringResource(R.string.ui_mode_dark)
    else -> stringResource(R.string.ui_mode_system)
}

@Composable
private fun phoneLanguageLabel(): String {
    val phone = AppLanguage.phoneLocale
    val name = AppLanguage.choices.firstOrNull { java.util.Locale.forLanguageTag(it.tag).language == phone.language }?.name
        ?: phone.getDisplayLanguage(phone).replaceFirstChar { it.titlecase(phone) }
    return stringResource(R.string.language_phone_named, name)
}

@Composable
private fun updateStatus(): String = AppUpdate.available?.let { stringResource(R.string.settings_update_available, it.version) }
    ?: stringResource(if (AppUpdate.checking) R.string.settings_checking else R.string.settings_up_to_date)

// ── Search ────────────────────────────────────────────────────────────────────

/** One searchable setting: where it lives ([key] = its row there; null = the category itself) and its words. */
private class Entry(val page: SettingsPage, val key: String?, val title: String, val words: List<String>)

private class Hit(val page: SettingsPage, val key: String?, val title: String, val line: String?)

/** Every setting, in the app's language. Built per search; cheap. */
private fun entries(): List<Entry> = buildList {
    SettingsPage.entries.forEach { add(Entry(it, null, str(it.titleRes), emptyList())) }
    val pb = SettingsPage.Playback
    add(Entry(pb, "controls", str(R.string.ui_player_controls), listOf(str(R.string.ui_player_controls_pill), str(R.string.ui_player_controls_on_video))))
    add(Entry(pb, "speed", str(R.string.settings_speed_remember_title), listOf(str(R.string.settings_speed_keep), str(R.string.bar_speed_remember))))
    add(Entry(pb, "seek", str(R.string.settings_seek_step_title), listOf(str(R.string.settings_seek_step_line)) + SeekStep.choices.map { str(R.string.settings_seconds_short, it.toString()) }))
    add(Entry(pb, "chapters", str(R.string.settings_chapters_open_title), listOf(str(R.string.settings_chapters_open_line), str(R.string.bar_chapters))))
    add(Entry(pb, "pip", str(R.string.settings_auto_pip_title), listOf(str(R.string.settings_auto_pip_line))))
    add(Entry(pb, "music", str(R.string.settings_music_normal_title), listOf(str(R.string.settings_music_normal_line))))
    add(Entry(pb, "zoom", str(R.string.settings_zoom_fill_title), listOf(str(R.string.settings_zoom_fill_on), str(R.string.settings_zoom_fill_off))))
    add(Entry(pb, "audio", str(R.string.bar_audio_only), listOf(str(R.string.settings_audio_only_line))))
    add(Entry(pb, "live", str(R.string.settings_live_title), listOf(str(R.string.settings_live_line), str(R.string.settings_live_words))))
    val cc = SettingsPage.Captions
    add(Entry(cc, "show", str(R.string.captions_show), listOf(str(R.string.captions_show_never), str(R.string.captions_show_always))))
    add(Entry(cc, "look", str(R.string.captions_look), CaptionSettings.Look.entries.map { str(it.labelRes) }))
    add(Entry(cc, "size", str(R.string.captions_size), CaptionSettings.sizes.map { str(it.second) }))
    add(Entry(cc, "kept", str(R.string.captions_kept_languages), emptyList()))
    val sb = SettingsPage.SponsorBlock
    add(Entry(sb, "sb", str(R.string.settings_sponsorblock), listOf(str(R.string.ui_sponsorblock_line))))
    SponsorBlock.Category.entries.forEach { add(Entry(sb, "sb_${it.key}", it.label, listOf(str(it.shortRes)))) }
    add(Entry(SettingsPage.Shorts, "shorts", str(R.string.ui_shorts), listOf(str(R.string.ui_shorts_on_line))))
    val ap = SettingsPage.Appearance
    add(Entry(ap, "mode", str(R.string.settings_mode), listOf(str(R.string.ui_mode_system), str(R.string.ui_mode_light), str(R.string.ui_mode_dark))))
    add(Entry(ap, "amoled", str(R.string.settings_amoled_title), listOf(str(R.string.settings_amoled_line), "AMOLED", "OLED")))
    add(Entry(ap, "theme", str(R.string.settings_theme), listOf(str(R.string.ui_theme_wallpaper)) + SKIPII_THEMES.map { it.name }))
    add(Entry(SettingsPage.Language, "lang", str(R.string.language_title), AppLanguage.choices.map { it.name } + str(R.string.language_phone)))
    val nt = SettingsPage.Notifications
    add(Entry(nt, "system", str(R.string.settings_system_notifications), listOf(str(R.string.settings_system_notifications_line))))
    Bells.on.values.forEach { ch -> ch.name?.let { add(Entry(nt, "bell_${ch.id}", it, listOf(str(R.string.settings_bell_line)))) } }
    add(Entry(SettingsPage.Account, "account", str(if (Account.signedIn) R.string.ui_sign_out else R.string.ui_sign_in), listOfNotNull(Account.profile?.name, "Google")))
    val ab = SettingsPage.About
    add(Entry(ab, "version", str(R.string.settings_version, BuildConfig.VERSION_NAME), listOf(str(R.string.settings_checking))))
    add(Entry(ab, "crash", str(R.string.settings_crash_title), listOf(str(R.string.settings_crash_none), str(R.string.settings_crash_saved))))
    add(Entry(ab, "source", str(R.string.settings_source), listOf(str(R.string.settings_source_line), "GitHub")))
}

/** Case and accents don't matter ("espanol" finds Español). */
private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Rows a TV doesn't show (PlaybackPage): never found by a search there. */
private val PhoneOnlyRows = setOf("controls", "chapters", "pip", "zoom", "live")

private fun hits(query: String, tv: Boolean = false): List<Hit> {
    val q = fold(query.trim())
    if (q.isEmpty()) return emptyList()
    return entries().filterNot { tv && it.key in PhoneOnlyRows }.mapNotNull { e ->
        when {
            fold(e.title).contains(q) -> Hit(e.page, e.key, e.title, null)
            else -> e.words.firstOrNull { fold(it).contains(q) }?.let { Hit(e.page, e.key, e.title, it) }
        }
    }.let { found ->
        // A category and its own switch share a name (SponsorBlock, Shorts): keep the switch, it's the more exact spot.
        found.filterNot { hit -> hit.key == null && found.any { it.key != null && it.page == hit.page && it.title == hit.title } }
    }.take(24)
}

@Composable
private fun Results(query: String, onPick: (Hit) -> Unit) {
    val c = MaterialTheme.colorScheme
    val found = hits(query, tv = LocalWindowLayout.current.tv)
    if (found.isEmpty()) {
        Text(
            stringResource(R.string.settings_no_results, query.trim()),
            color = c.onSurfaceVariant, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 12.dp),
        )
        return
    }
    val mark = SpanStyle(background = c.primaryContainer, color = c.onPrimaryContainer)
    val settingsTitle = stringResource(R.string.settings_title)
    Spacer(Modifier.height(16.dp))
    Joined(found.size) { i, shape ->
        val hit = found[i]
        Tile(
            hit.page.icon,
            marked(hit.title, query, mark),
            hit.line?.let { marked(it, query, mark) },
            shape,
            crumb = if (hit.key == null) settingsTitle else stringResource(hit.page.titleRes),
        ) { onPick(hit) }
    }
}

/** [text] with every match of [query] marked (only when folding kept its length, so the spots line up). */
private fun marked(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    val folded = fold(text)
    val q = fold(query.trim())
    if (q.isEmpty() || folded.length != text.length) return@buildAnnotatedString
    var from = 0
    while (true) {
        val at = folded.indexOf(q, from)
        if (at < 0) break
        addStyle(style, at, at + q.length)
        from = at + q.length
    }
}

// ── Finding a setting: scroll to it and light it up ───────────────────────────

/** The setting a search result opened, found once the category has laid out. */
private class Find(var key: String?) {
    val rows = HashMap<String, LayoutCoordinates>()
    var content: LayoutCoordinates? = null
    var scroll: ScrollState? = null
    var topPx = 0f
    var lit by mutableStateOf<String?>(null)

    suspend fun reach() {
        val key = key ?: return
        // The same row picked again (wide: its category stays open) lights up again.
        lit = null
        // Once the category has slid in.
        delay(380)
        val row = rows[key]?.takeIf { it.isAttached } ?: return
        val page = content?.takeIf { it.isAttached } ?: return
        val y = page.localPositionOf(row, Offset.Zero).y
        scroll?.animateScrollTo((y - topPx).toInt().coerceAtLeast(0), tween(420, easing = SettingsEase))
        lit = key
    }
}

private val LocalFind = staticCompositionLocalOf<Find?> { null }

/**
 * One setting's surface. [key] lets search find it: it then lights up (container → primary container and
 * back, both opaque) after the page scrolls to it.
 */
@Composable
private fun SettingBox(key: String?, shape: Shape, modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = MaterialTheme.colorScheme
    val find = LocalFind.current
    val glow = remember { Animatable(0f) }
    if (key != null && find != null) {
        LaunchedEffect(find.lit) {
            if (find.lit == key) {
                glow.animateTo(1f, tween(260))
                glow.animateTo(0f, tween(900, delayMillis = 700))
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { if (key != null) find?.rows?.put(key, it) }
            .clip(shape)
            .drawBehind { drawRect(lerp(c.surfaceContainer, c.primaryContainer, glow.value)) }
            .padding(horizontal = padding, vertical = 15.dp),
        content = content,
    )
}

/** Title and line, with a switch; the whole row toggles. On an RTL-language phone the switch sits left, mirrored (0.5.64). */
@Composable
private fun SwitchSetting(
    key: String,
    shape: Shape,
    title: String,
    line: String,
    checked: Boolean,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val rtl = rememberRtlLanguage()
    val toggle = @Composable {
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = c.primary))
        }
    }
    SettingBox(key, shape, Modifier.pressable(enabled = enabled, pressedScale = 0.98f) { onChange(!checked) }) {
        // Not available here (the line says why): dimmed, as SponsorBlock's segments are while it's off.
        Row(Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f }, verticalAlignment = Alignment.CenterVertically) {
            if (rtl) {
                toggle()
                Spacer(Modifier.width(14.dp))
            }
            if (icon != null) {
                IconSquare(icon)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Text(line, color = c.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (!rtl) {
                Spacer(Modifier.width(12.dp))
                toggle()
            }
        }
    }
}

/** A title (and line) over a row of choice chips. */
@Composable
private fun <T> ChipsSetting(
    key: String,
    shape: Shape,
    title: String,
    line: String?,
    options: List<Pair<T, String>>,
    selected: T,
    icon: ImageVector? = null,
    onPick: (T) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    SettingBox(key, shape) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                IconSquare(icon)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (line != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(line, color = c.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Chips(options, selected, onPick = onPick)
    }
}

/** A row of choice chips; it scrolls, as every row of chips does (#24). */
@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onPick: (T) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .pressable(enabled = enabled) { onPick(value) }
                    .clip(RoundedCornerShape(if (on) 18.dp else 10.dp))
                    // Full primary when picked, as the quick toggles: secondaryContainer was too close to the
                    // others' tone in some themes to tell which was on (Derek).
                    .background(if (on) c.primary else c.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text(label, color = if (on) c.onPrimary else c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A row that goes somewhere (a link, a check). */
@Composable
private fun LinkSetting(key: String, shape: Shape, icon: ImageVector, title: String, line: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    SettingBox(key, shape, Modifier.pressable(pressedScale = 0.98f, onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Text(line, color = c.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp),
    )
}

// ── Categories ────────────────────────────────────────────────────────────────

@Composable
private fun PlaybackPage(player: PlayerState) {
    val pipSupported = LocalPictureInPicture.current?.supported != false
    // A TV (TvPlayer) has no playback pill, chapter list, picture-in-picture, pinch or live activity: only what still
    // applies there is shown.
    val tv = LocalWindowLayout.current.tv
    val controls = when {
        tv -> listOf(1)
        pipSupported -> listOf(0, 1, 2, 3)
        else -> listOf(0, 1, 2)
    }
    val picture = if (tv) listOf(0, 1, 3) else listOf(0, 1, 2, 3)
    Spacer(Modifier.height(14.dp))
    // The controls and how they behave.
    Joined(controls.size) { i, shape ->
        when (controls[i]) {
            0 -> ChipsSetting(
                "controls", shape,
                stringResource(R.string.ui_player_controls),
                stringResource(if (PlayerControls.inBar) R.string.ui_player_controls_in_bar_line else R.string.ui_player_controls_on_video_line),
                listOf(true to stringResource(R.string.ui_player_controls_pill), false to stringResource(R.string.ui_player_controls_on_video)),
                PlayerControls.inBar,
                icon = Icons.Rounded.Tune,
            ) { PlayerControls.set(it) }
            // Its icon is the −/+ buttons' own glyph, so it changes with the pick (#36).
            1 -> ChipsSetting(
                "seek", shape,
                stringResource(R.string.settings_seek_step_title),
                stringResource(R.string.settings_seek_step_line),
                SeekStep.choices.map { it to stringResource(R.string.settings_seconds_short, it.toString()) },
                SeekStep.seconds,
                icon = seekIcon(forward = true),
            ) { SeekStep.set(it) }
            2 -> SwitchSetting(
                "chapters", shape,
                stringResource(R.string.settings_chapters_open_title),
                stringResource(R.string.settings_chapters_open_line),
                ChaptersStayOpen.on,
                icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
            ) { ChaptersStayOpen.set(it) }
            else -> SwitchSetting(
                "pip", shape,
                stringResource(R.string.settings_auto_pip_title),
                stringResource(R.string.settings_auto_pip_line),
                AutoPip.on,
                icon = Icons.Rounded.PictureInPictureAlt,
            ) { AutoPip.set(it) }
        }
    }
    Spacer(Modifier.height(14.dp))
    // Speed, picture and sound.
    Joined(picture.size) { i, shape ->
        when (picture[i]) {
            0 -> SwitchSetting(
                "speed", shape,
                stringResource(R.string.settings_speed_remember_title),
                if (PlaybackSpeed.remember) stringResource(R.string.settings_speed_remember_on, PlaybackSpeed.label(PlaybackSpeed.saved))
                else stringResource(R.string.settings_speed_remember_off),
                PlaybackSpeed.remember,
                icon = Icons.Rounded.Speed,
            ) { if (it != PlaybackSpeed.remember) player.toggleRememberSpeed() }
            1 -> SwitchSetting(
                "music", shape,
                stringResource(R.string.settings_music_normal_title),
                stringResource(R.string.settings_music_normal_line),
                PlaybackSpeed.musicAtNormal,
                icon = Icons.Rounded.MusicNote,
            ) { PlaybackSpeed.chooseMusicAtNormal(it) }
            2 -> SwitchSetting(
                "zoom", shape,
                stringResource(R.string.settings_zoom_fill_title),
                stringResource(if (FullscreenZoom.fill) R.string.settings_zoom_fill_on else R.string.settings_zoom_fill_off),
                FullscreenZoom.fill,
                icon = Icons.Rounded.ZoomOutMap,
            ) { player.setZoomFillSetting(it) }
            else -> SwitchSetting(
                "audio", shape,
                stringResource(R.string.bar_audio_only),
                stringResource(R.string.settings_audio_only_line),
                AudioOnly.enabled,
                icon = Icons.Rounded.Headphones,
            ) { player.setAudioOnly(it) }
        }
    }
    Spacer(Modifier.height(14.dp))
    // Off screen.
    if (!tv) LiveActivitySetting(player)
}

/**
 * Playback controls in a live activity once the app is off screen (LiveActivity.kt). Turning it on asks for
 * notifications (Android 13+); with Live Updates off for Skipii in Android's settings, a row under it opens that page.
 */
@Composable
private fun LiveActivitySetting(player: PlayerState) {
    val context = LocalContext.current
    // Read again whenever the page comes back (from Android's settings, say).
    val supported = remember { LiveControls.supported(context) }
    var allowed by remember { mutableStateOf(LiveControls.canPromote(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = LiveControls.canPromote(context)
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = LiveControls.canPromote(context)
        if (!allowed) {
            player.actionMessage = str(R.string.settings_live_allow_line)
            LiveControls.openSettings(context)
        }
    }
    val blocked = supported && LiveControls.on && !allowed
    Joined(if (blocked) 2 else 1) { i, shape ->
        if (i == 0) {
            SwitchSetting(
                "live", shape,
                stringResource(R.string.settings_live_title),
                stringResource(
                    when {
                        !supported -> R.string.settings_live_needs
                        LiveControls.on && LiveControls.refusedBySystem -> R.string.settings_live_refused
                        else -> R.string.settings_live_line
                    },
                ),
                LiveControls.on && supported,
                icon = Icons.Rounded.Sensors,
                enabled = supported,
            ) { on ->
                LiveControls.set(on)
                if (on && !LiveControls.canPromote(context)) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        player.actionMessage = str(R.string.settings_live_allow_line)
                        LiveControls.openSettings(context)
                    }
                }
            }
        } else {
            LinkSetting(
                "live_allow", shape, Icons.Rounded.Notifications,
                stringResource(R.string.settings_live_allow_title),
                stringResource(R.string.settings_live_allow_line),
            ) { LiveControls.openSettings(context) }
        }
    }
}

@Composable
private fun CaptionsPage() {
    val c = MaterialTheme.colorScheme
    val kept = CaptionTranslator.downloaded.filter { it != "en" }.sortedBy { CaptionNames.language(it) }
    val rows = if (kept.isEmpty()) 4 else 5
    Spacer(Modifier.height(14.dp))
    Joined(rows) { i, shape ->
        when (i) {
            0 -> SettingBox("preview", shape, padding = 8.dp) { CaptionPreview() }
            1 -> ChipsSetting(
                "show", shape, stringResource(R.string.captions_show), null,
                CaptionSettings.Show.entries.map { it to captionShowLabel(it) },
                CaptionSettings.show,
            ) { CaptionSettings.chooseShow(it) }
            2 -> ChipsSetting(
                "look", shape, stringResource(R.string.captions_look), null,
                CaptionSettings.Look.entries.map { it to stringResource(it.labelRes) },
                CaptionSettings.look,
            ) { CaptionSettings.chooseLook(it) }
            3 -> ChipsSetting(
                "size", shape, stringResource(R.string.captions_size), null,
                CaptionSettings.sizes.map { it.first to stringResource(it.second) },
                CaptionSettings.size,
            ) { CaptionSettings.chooseSize(it) }
            else -> SettingBox("kept", shape) {
                Text(stringResource(R.string.captions_kept_languages), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kept.forEach { lang ->
                        Row(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(c.surfaceContainerHighest).padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(CaptionNames.language(lang), color = c.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                            Box(Modifier.size(34.dp).pressable { CaptionTranslator.delete(lang) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.captions_remove_language, CaptionNames.language(lang)), tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SponsorBlockPage() {
    val c = MaterialTheme.colorScheme
    Spacer(Modifier.height(14.dp))
    Joined(1) { _, shape ->
        SwitchSetting(
            "sb", shape, stringResource(R.string.settings_sponsorblock), stringResource(R.string.ui_sponsorblock_line),
            SponsorBlock.enabled, icon = Icons.Rounded.FastForward,
        ) { SponsorBlock.updateEnabled(it) }
    }
    Spacer(Modifier.height(12.dp))
    val categories = SponsorBlock.Category.entries
    // Off: the segments stay in place, dimmed, so a search can still point at one.
    Box(Modifier.graphicsLayer { alpha = if (SponsorBlock.enabled) 1f else 0.4f }) {
        Joined(categories.size) { i, shape ->
            val category = categories[i]
            SettingBox("sb_${category.key}", shape) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(category.color))
                    Spacer(Modifier.width(10.dp))
                    Text(category.label, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                Chips(
                    SponsorBlock.Action.entries.map { it to stringResource(it.labelRes) },
                    SponsorBlock.actionFor(category),
                    enabled = SponsorBlock.enabled,
                ) { SponsorBlock.setAction(category, it) }
            }
        }
    }
    Note(stringResource(R.string.ui_sponsorblock_credit))
}

@Composable
private fun ShortsPage() {
    val context = LocalContext.current
    Spacer(Modifier.height(14.dp))
    Joined(1) { _, shape ->
        SwitchSetting(
            "shorts", shape, stringResource(R.string.ui_shorts),
            stringResource(if (ShortsSetting.enabled) R.string.ui_shorts_on_line else R.string.ui_shorts_off_line),
            ShortsSetting.enabled, icon = Icons.Rounded.SlowMotionVideo,
        ) { ShortsSetting.set(context, it) }
    }
}

@Composable
private fun AppearancePage() {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val dark = isDarkTheme()
    Spacer(Modifier.height(14.dp))
    Joined(3) { i, shape ->
        if (i == 0) {
            ChipsSetting(
                "mode", shape, stringResource(R.string.settings_mode), null,
                listOf("system", "light", "dark").map { it to modeLabel(it) },
                ThemeSettings.mode,
                icon = Icons.Rounded.DarkMode,
            ) { ThemeSettings.chooseMode(it) }
        } else if (i == 1) {
            // Applies whenever the app is dark (Dark, or System at night); Light never is, so it's dimmed there.
            val light = ThemeSettings.mode == "light"
            SwitchSetting(
                "amoled", shape, stringResource(R.string.settings_amoled_title),
                stringResource(if (light) R.string.settings_amoled_light_line else R.string.settings_amoled_line),
                ThemeSettings.amoled, icon = Icons.Rounded.Contrast, enabled = !light,
            ) { ThemeSettings.chooseAmoled(it) }
        } else {
            SettingBox("theme", shape) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconSquare(Icons.Rounded.Palette)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.settings_theme), fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                val wallpaper = stringResource(R.string.ui_theme_wallpaper)
                val choices = buildList {
                    if (ThemeSettings.wallpaperAvailable) add(null to wallpaper)
                    SKIPII_THEMES.forEach { add(it.hex to it.name) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    choices.forEach { (hex, name) ->
                        val selected = ThemeSettings.seed?.lowercase() == hex?.lowercase()
                        val scheme = themedScheme(context, hex, dark)
                        Column(Modifier.width(72.dp).pressable { ThemeSettings.choose(hex) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(if (selected) CookieShape else CircleShape)
                                    .background(scheme.surface)
                                    .padding(5.dp)
                                    .clip(if (selected) CookieShape else CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            0f to scheme.primary, 0.5f to scheme.primary,
                                            0.5f to scheme.primaryContainer, 1f to scheme.primaryContainer,
                                        ),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Rounded.Check, stringResource(R.string.ui_selected), tint = scheme.onPrimary, modifier = Modifier.size(22.dp))
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
    }
}

@Composable
private fun LanguagePage() {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val chosen = AppLanguage.pickedChoice
    val options = listOf("" to phoneLanguageLabel()) + AppLanguage.choices.map { it.tag to it.name }
    Spacer(Modifier.height(14.dp))
    Joined(options.size) { i, shape ->
        val (tag, label) = options[i]
        val on = tag == chosen
        SettingBox(if (i == 0) "lang" else null, shape, Modifier.pressable(pressedScale = 0.98f) { context.findActivity()?.let { AppLanguage.pick(it, tag) } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Each language named in itself, so one picked by mistake is easy to undo.
                Text(label, fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(
                    if (on) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    if (on) stringResource(R.string.ui_selected) else null,
                    tint = if (on) c.primary else c.onSurfaceVariant,
                )
            }
        }
    }
    Note(stringResource(R.string.language_line))
}

@Composable
private fun NotificationsPage(player: PlayerState) {
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val toggle = rememberBellToggle { player.actionMessage = it }
    // The channels as the page opened: one turned off stays listed (bell off) so it can go back on.
    val channels = remember { Bells.on.values.sortedBy { it.name?.lowercase() ?: it.id } }
    Spacer(Modifier.height(14.dp))
    if (channels.isEmpty()) {
        Joined(1) { _, shape ->
            SettingBox(null, shape) {
                Text(stringResource(R.string.settings_bells_empty), color = c.onSurfaceVariant, fontSize = 13.5.sp, lineHeight = 19.sp)
            }
        }
    } else {
        Joined(channels.size) { i, shape ->
            val channel = channels[i]
            val on = Bells.isOn(channel.id)
            SettingBox("bell_${channel.id}", shape, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelAvatar(channel.avatar, channel.name.orEmpty(), size = 40)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(channel.name ?: channel.id, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(if (on) R.string.settings_bell_line else R.string.settings_off),
                            color = c.onSurfaceVariant, fontSize = 12.5.sp,
                        )
                    }
                    val key by animateColorAsState(if (on) c.primary else c.surfaceContainerHighest, tween(260), label = "bellKey")
                    Box(
                        Modifier
                            .size(44.dp)
                            .pressable { toggle(channel) }
                            .clip(CircleShape)
                            .drawBehind { drawRect(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (on) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                            stringResource(if (on) R.string.app_bell_on_description else R.string.app_bell_off_description),
                            tint = if (on) c.onPrimary else c.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Joined(1) { _, shape ->
        LinkSetting(
            "system", shape, Icons.Rounded.Notifications,
            stringResource(R.string.settings_system_notifications), stringResource(R.string.settings_system_notifications_line),
        ) {
            context.openOrSay(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            ) { player.actionMessage = it }
        }
    }
}

@Composable
private fun AccountPage(onSignIn: () -> Unit, onSignedOut: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val profile = Account.profile
    Spacer(Modifier.height(14.dp))
    if (profile == null) {
        Joined(1) { _, shape ->
            SettingBox("account", shape, padding = 20.dp) {
                // The pitch's title is already the page's line, right above.
                Text(stringResource(R.string.ui_account_pitch_body), color = c.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PillButton(stringResource(R.string.ui_sign_in_with_google), onSignIn)
                }
            }
        }
        return
    }
    Joined(2) { i, shape ->
        if (i == 0) {
            SettingBox("account", shape, padding = 20.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ChannelAvatar(profile.avatar, profile.name, size = 72)
                    Spacer(Modifier.height(12.dp))
                    Text(profile.name, fontFamily = RoundedHeavy, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, textAlign = TextAlign.Center)
                    profile.handle?.let { Text(it, color = c.onSurfaceVariant, fontSize = 14.sp) }
                    Spacer(Modifier.height(18.dp))
                    PillButton(stringResource(R.string.ui_sign_out), { Account.signOut(); onSignedOut() }, Icons.AutoMirrored.Rounded.Logout)
                }
            }
        } else {
            SettingBox("sync", shape) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Sync, null, tint = c.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.settings_sync_note), color = c.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun AboutPage(player: PlayerState) {
    val context = LocalContext.current
    var whatsNew by remember { mutableStateOf(false) }
    Spacer(Modifier.height(14.dp))
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(R.string.settings_crash_share)
    Joined(3) { i, shape ->
        if (i == 0) {
            LinkSetting("version", shape, Icons.Rounded.SystemUpdateAlt, stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), updateStatus()) {
                if (AppUpdate.available != null) whatsNew = true else AppUpdate.checkNow()
            }
        } else if (i == 1) {
            // GitHub #55: what crashed (or how Android closed Skipii), to paste into an issue.
            val line = stringResource(if (CrashLog.newest > 0L) R.string.settings_crash_saved else R.string.settings_crash_none)
            LinkSetting("crash", shape, Icons.Rounded.BugReport, stringResource(R.string.settings_crash_title), line) {
                scope.launch {
                    val text = withContext(Dispatchers.IO) { CrashLog.report(context) }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Skipii crash report")
                        .putExtra(Intent.EXTRA_TEXT, text)
                    context.openOrSay(Intent.createChooser(send, shareTitle)) { player.actionMessage = it }
                    CrashLog.markShared()
                }
            }
        } else {
            LinkSetting("source", shape, Icons.Rounded.Code, stringResource(R.string.settings_source), stringResource(R.string.settings_source_line)) {
                context.openOrSay(Intent(Intent.ACTION_VIEW, Uri.parse(SourceUrl))) { player.actionMessage = it }
            }
        }
    }
    if (whatsNew) AppUpdate.available?.let { WhatsNewSheet(it, onDismiss = { whatsNew = false }) }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

// Brand marks, copied from Moodii's BrandIcons.kt.
private const val InstagramPath =
    "M12 0C8.74 0 8.333.015 7.053.072 5.775.132 4.905.333 4.14.63c-.789.306-1.459.717-2.126 1.384S.935 3.35.63 4.14C.333 4.905.131 5.775.072 7.053.012 8.333 0 8.74 0 12s.015 3.667.072 4.947c.06 1.277.261 2.148.558 2.913.306.788.717 1.459 1.384 2.126.667.666 1.336 1.079 2.126 1.384.766.296 1.636.499 2.913.558C8.333 23.988 8.74 24 12 24s3.667-.015 4.947-.072c1.277-.06 2.148-.262 2.913-.558.788-.306 1.459-.718 2.126-1.384.666-.667 1.079-1.335 1.384-2.126.296-.765.499-1.636.558-2.913.06-1.28.072-1.687.072-4.947s-.015-3.667-.072-4.947c-.06-1.277-.262-2.149-.558-2.913-.306-.789-.718-1.459-1.384-2.126C21.319 1.347 20.651.935 19.86.63c-.765-.297-1.636-.499-2.913-.558C15.667.012 15.26 0 12 0zm0 2.16c3.203 0 3.585.016 4.85.071 1.17.055 1.805.249 2.227.415.562.217.96.477 1.382.896.419.42.679.819.896 1.381.164.422.36 1.057.413 2.227.057 1.266.07 1.646.07 4.85s-.015 3.585-.074 4.85c-.061 1.17-.256 1.805-.421 2.227-.224.562-.479.96-.899 1.382-.419.419-.824.679-1.38.896-.42.164-1.065.36-2.235.413-1.274.057-1.649.07-4.859.07-3.211 0-3.586-.015-4.859-.074-1.171-.061-1.816-.256-2.236-.421-.569-.224-.96-.479-1.379-.899-.421-.419-.69-.824-.9-1.38-.165-.42-.359-1.065-.42-2.235-.045-1.26-.061-1.649-.061-4.844 0-3.196.016-3.586.061-4.861.061-1.17.255-1.814.42-2.234.21-.57.479-.96.9-1.381.419-.419.81-.689 1.379-.898.42-.166 1.051-.361 2.221-.421 1.275-.045 1.65-.06 4.859-.06l.045.03zm0 3.678c-3.405 0-6.162 2.76-6.162 6.162 0 3.405 2.76 6.162 6.162 6.162 3.405 0 6.162-2.76 6.162-6.162 0-3.405-2.76-6.162-6.162-6.162zM12 16c-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4-1.79 4-4 4zm7.846-10.405c0 .795-.646 1.44-1.44 1.44-.795 0-1.44-.646-1.44-1.44 0-.794.646-1.439 1.44-1.439.793-.001 1.439.645 1.439 1.439z"
private const val XPath =
    "M18.901 1.153h3.68l-8.04 9.19L24 22.846h-7.406l-5.8-7.584-6.638 7.584H.474l8.6-9.83L0 1.154h7.594l5.243 6.932ZM17.61 20.644h2.039L6.486 3.24H4.298Z"
private const val ThreadsPath =
    "M12.186 24h-.007c-3.581-.024-6.334-1.205-8.184-3.509C2.35 18.44 1.5 15.586 1.472 12.01v-.017c.03-3.579.879-6.43 2.525-8.482C5.845 1.205 8.6.024 12.18 0h.014c2.746.02 5.043.725 6.826 2.098 1.677 1.29 2.858 3.13 3.509 5.467l-2.04.569c-1.104-3.96-3.898-5.984-8.304-6.015-2.91.022-5.11.936-6.54 2.717C4.307 6.504 3.616 8.914 3.589 12c.027 3.086.718 5.496 2.057 7.164 1.43 1.783 3.631 2.698 6.54 2.717 2.623-.02 4.358-.631 5.8-2.045 1.647-1.613 1.618-3.593 1.09-4.798-.31-.71-.873-1.3-1.634-1.75-.192 1.352-.622 2.446-1.284 3.272-.886 1.102-2.14 1.704-3.73 1.79-1.202.065-2.361-.218-3.259-.801-1.063-.689-1.685-1.74-1.752-2.964-.065-1.19.408-2.285 1.33-3.082.88-.76 2.119-1.207 3.583-1.291a13.853 13.853 0 0 1 3.02.142c-.126-.742-.375-1.332-.75-1.757-.513-.586-1.308-.883-2.359-.89h-.029c-.844 0-1.992.232-2.721 1.32L7.734 7.847c.98-1.454 2.568-2.256 4.478-2.256h.044c3.194.02 5.097 1.975 5.287 5.388.108.046.216.094.321.142 1.49.7 2.58 1.761 3.154 3.07.797 1.82.871 4.79-1.548 7.158-1.85 1.81-4.094 2.628-7.277 2.65Zm1.003-11.69c-.242 0-.487.007-.739.021-1.836.103-2.98.946-2.916 2.143.067 1.256 1.452 1.839 2.784 1.767 1.224-.065 2.818-.543 3.086-3.71a10.5 10.5 0 0 0-2.215-.221Z"
