package com.decio.skipii

import android.text.Spanned
import android.text.style.URLSpan
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.text.HtmlCompat
import java.net.URI
import java.net.URLDecoder
import androidx.compose.ui.unit.sp

/** A link in a video's description: [start]..[end) of [Description.text] goes to [url]. */
@Immutable
data class DescriptionLink(val start: Int, val end: Int, val url: String)

/**
 * A video's description with its links. YouTube shows long links cut short ("https://play.google.com/store/apps/de...")
 * and keeps the real target only in the link itself, so flattening NewPipe's HTML to text lost both the
 * rest of the address and the tap. A cut link is shown whole here ([shownText]).
 */
@Immutable
data class Description(val text: String, val links: List<DescriptionLink> = emptyList()) {
    companion object {
        val Empty = Description("")

        /** From NewPipe's HTML description (`<a href>` around every link, timestamp, hashtag and @mention). */
        fun fromHtml(html: String): Description {
            val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            return fromSpanned(spanned)
        }

        /** From a plain-text description: bare web addresses become links. */
        fun fromPlain(text: String): Description = linkifyBare(text.trim(), emptyList())

        /** [text] with [links], plus its bare web addresses outside them. */
        fun withBareLinks(text: String, links: List<DescriptionLink>): Description = linkifyBare(text, links)

        private fun fromSpanned(spanned: Spanned): Description {
            val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
                .sortedBy { spanned.getSpanStart(it) }
            val out = StringBuilder()
            val links = ArrayList<DescriptionLink>()
            var at = 0
            for (span in spans) {
                val s = spanned.getSpanStart(span)
                val e = spanned.getSpanEnd(span)
                if (s < at || e <= s) continue
                out.append(spanned, at, s)
                val url = unwrapRedirect(span.url ?: "")
                val shown = shownText(spanned.substring(s, e), url)
                links += DescriptionLink(out.length, out.length + shown.length, url)
                out.append(shown)
                at = e
            }
            out.append(spanned, at, spanned.length)
            // Trim, moving the links with the text.
            val lead = out.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) out.length else it }
            val text = out.toString().trim()
            val moved = links.mapNotNull { l ->
                val s = (l.start - lead).coerceAtLeast(0)
                val e = (l.end - lead).coerceAtMost(text.length)
                if (e > s && l.url.isNotBlank()) l.copy(start = s, end = e) else null
            }
            return linkifyBare(text, moved)
        }

        private val BareUrl = Regex("""https?://[^\s<>"]+""")

        /** Bare http(s) addresses outside [links] become links too (a trailing . , ) ! ? stays text). */
        private fun linkifyBare(text: String, links: List<DescriptionLink>): Description {
            val extra = BareUrl.findAll(text).mapNotNull { m ->
                val trimmed = m.value.trimEnd('.', ',', ')', '!', '?', ';', ':', '\'', '"')
                val r = m.range.first until m.range.first + trimmed.length
                if (trimmed.length < 10 || links.any { it.start < r.last + 1 && r.first < it.end }) null
                else DescriptionLink(r.first, r.last + 1, trimmed)
            }.toList()
            return Description(text, (links + extra).sortedBy { it.start })
        }
    }
}

/**
 * The words a link shows: YouTube's own text, unless that text is the address cut short (or the
 * address itself) — then the whole address, written the way YouTube started it (with or without
 * the scheme / "www.").
 */
internal fun shownText(display: String, url: String): String {
    val cut = display.trim().removeSuffix("...").removeSuffix("…").trimEnd()
    if (cut.length < 4 || url.isBlank()) return display
    val bare = url.substringAfter("://")
    return when {
        url.startsWith(cut) -> url
        bare.startsWith(cut) -> bare
        bare.removePrefix("www.").startsWith(cut) -> bare.removePrefix("www.")
        else -> display
    }
}

/** youtube.com/redirect?q=<target> → the target (NewPipe usually unwraps these already). */
internal fun unwrapRedirect(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: return url
    if (!isYouTubeHost(uri.host) || uri.path != "/redirect") return url
    return queryParam(uri.rawQuery, "q")?.takeIf { it.startsWith("http") } ?: url
}

/** Where a description link goes in Skipii. */
sealed interface LinkTarget {
    /** A timestamp of the video being watched. */
    data class Seek(val ms: Long) : LinkTarget
    /** Another YouTube video (or short): plays in Skipii. */
    data class Video(val id: String) : LinkTarget
    /** A YouTube channel (/channel/UC…, /@handle, /c/…, /user/…): the channel page. */
    data class Channel(val url: String) : LinkTarget
    /** A #hashtag: searched for. */
    data class Search(val query: String) : LinkTarget
    /** Anything else: the browser (or whichever app takes it). */
    data class Web(val url: String) : LinkTarget
}

/** What tapping [url] in the description of video [currentId] should do. */
fun linkTarget(url: String, currentId: String?): LinkTarget {
    val target = unwrapRedirect(url)
    val uri = runCatching { URI(target) }.getOrNull() ?: return LinkTarget.Web(target)
    val host = uri.host?.lowercase() ?: return LinkTarget.Web(target)
    val path = uri.path.orEmpty()
    val query = uri.rawQuery
    if (host == "youtu.be") {
        val id = path.trim('/').takeIf { VideoId.matches(it) } ?: return LinkTarget.Web(target)
        return videoOrSeek(id, queryParam(query, "t"), currentId)
    }
    if (!isYouTubeHost(host) || host.startsWith("music.")) return LinkTarget.Web(target)
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    val first = parts.firstOrNull()
    return when {
        path == "/watch" -> {
            val id = queryParam(query, "v")?.takeIf { VideoId.matches(it) } ?: return LinkTarget.Web(target)
            videoOrSeek(id, queryParam(query, "t") ?: queryParam(query, "start"), currentId)
        }
        (first == "shorts" || first == "live" || first == "embed" || first == "v") && parts.size >= 2 && VideoId.matches(parts[1]) ->
            videoOrSeek(parts[1], queryParam(query, "t") ?: queryParam(query, "start"), currentId)
        first == "hashtag" && parts.size >= 2 -> LinkTarget.Search("#" + parts[1])
        first == "channel" && parts.size >= 2 -> LinkTarget.Channel("https://www.youtube.com/channel/${parts[1]}")
        first != null && first.startsWith("@") -> LinkTarget.Channel("https://www.youtube.com/$first")
        (first == "c" || first == "user") && parts.size >= 2 -> LinkTarget.Channel("https://www.youtube.com/$first/${parts[1]}")
        else -> LinkTarget.Web(target)
    }
}

private val VideoId = Regex("[A-Za-z0-9_-]{11}")

/** A link's start time (t= / start=), or null. */
fun linkStartMs(url: String): Long? {
    val uri = runCatching { URI(unwrapRedirect(url)) }.getOrNull() ?: return null
    return parseStartTime(queryParam(uri.rawQuery, "t") ?: queryParam(uri.rawQuery, "start"))
}

/** youtube.com/playlist?list=… → the playlist's id (a watch link with list= plays its video instead). */
fun linkPlaylistId(url: String): String? {
    val uri = runCatching { URI(unwrapRedirect(url)) }.getOrNull() ?: return null
    if (!isYouTubeHost(uri.host) || uri.host.lowercase().startsWith("music.") || uri.path != "/playlist") return null
    return queryParam(uri.rawQuery, "list")?.takeIf { it.isNotBlank() }
}

/**
 * YouTube links opened in Skipii from other apps (GitHub #59): a link tapped in a browser / chat
 * (ACTION_VIEW, the manifest's youtube.com / youtu.be filters) or shared to Skipii as text
 * (ACTION_SEND). MainActivity routes [pending] like a description link.
 */
object IncomingLinks {
    var pending by androidx.compose.runtime.mutableStateOf<String?>(null)

    private val AnyUrl = Regex("""https?://\S+""")

    fun take(intent: android.content.Intent?) {
        intent ?: return
        val url = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.dataString?.let { if (it.startsWith("http")) it else fromVndYouTube(it) }
            android.content.Intent.ACTION_SEND -> intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                ?.let { AnyUrl.find(it)?.value?.trimEnd('.', ',', ')', '!', '?', ';', '\'', '"') }
            else -> null
        } ?: return
        // Taken once: a window rebuilt with the same intent mustn't open it again.
        intent.action = null
        intent.data = null
        pending = url
    }
}

/**
 * vnd.youtube links (what some apps send straight to the YouTube app) as web links: "vnd.youtube:<id>",
 * "vnd.youtube://<id>?t=30", "vnd.youtube://www.youtube.com/watch?v=<id>", "vnd.youtube:///watch?v=<id>".
 */
internal fun fromVndYouTube(data: String): String? {
    if (!data.startsWith("vnd.youtube:", ignoreCase = true)) return null
    val rest = data.substring("vnd.youtube:".length).trimStart('/')
    if (rest.isEmpty()) return null
    val first = rest.substringBefore('?').substringBefore('/')
    val head = first.lowercase()
    return when {
        head == "youtu.be" || head == "youtube.com" || head.endsWith(".youtube.com") -> "https://$rest"
        head == "watch" || head in setOf("shorts", "live", "embed", "playlist", "channel", "c", "user") || head.startsWith("@") ->
            "https://www.youtube.com/$rest"
        VideoId.matches(first) -> {
            val query = rest.substringAfter('?', "")
            "https://www.youtube.com/watch?v=$first" + if (query.isNotEmpty()) "&$query" else ""
        }
        else -> null
    }
}

/**
 * Which app opens YouTube links on this phone (Settings › About › Open YouTube links). An app can't verify a domain
 * that isn't its own, so on Android 12+ Skipii only gets youtube.com / youtu.be links once it's allowed under its
 * "Open by default" — and not even then while the YouTube app (verified for them) still has "Open supported links"
 * on: Android keeps a verified app over a picked one. Hence two steps when the YouTube app has them.
 */
object YouTubeLinks {
    sealed interface Status {
        /** Every kind of YouTube link opens in Skipii. */
        data object Skipii : Status
        /** Another app (the YouTube app, a mod of it) takes them: its links have to be turned off first. */
        data class OtherApp(val packageName: String, val label: String) : Status
        /** A browser or Android's chooser takes them: only Skipii's own links to add. */
        data object NotYet : Status
    }

    /** One link per host Skipii takes (the hosts Android's Add links lists). */
    private val Samples = listOf(
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://youtube.com/watch?v=dQw4w9WgXcQ",
        "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://youtu.be/dQw4w9WgXcQ",
    )

    fun status(context: android.content.Context): Status {
        val pm = context.packageManager
        val own = context.packageName
        val browsers = handlers(pm, "https://example.com")
        var allOurs = true
        for (url in Samples) {
            val picked = pm.resolveActivity(view(url), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            if (picked == own) continue
            allOurs = false
            // Android's chooser isn't among a link's handlers; a browser is among example.com's.
            if (picked != null && picked !in browsers && picked in handlers(pm, url)) {
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(picked, 0)).toString() }.getOrDefault(picked)
                AppLog.i("Skipii", "links: $url opens in $picked")
                return Status.OtherApp(picked, label)
            }
        }
        AppLog.i("Skipii", "links: " + if (allOurs) "all open in Skipii" else "not all in Skipii, no other app")
        return if (allOurs) Status.Skipii else Status.NotYet
    }

    /** Android's "Open by default" page of [packageName] (app info below Android 12). */
    fun openByDefault(packageName: String): android.content.Intent {
        val pkg = android.net.Uri.parse("package:$packageName")
        return if (android.os.Build.VERSION.SDK_INT >= 31) android.content.Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg)
        else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }

    private fun view(url: String) = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        .addCategory(android.content.Intent.CATEGORY_BROWSABLE)

    private fun handlers(pm: android.content.pm.PackageManager, url: String): Set<String> =
        pm.queryIntentActivities(view(url), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .mapTo(HashSet()) { it.activityInfo.packageName }
}

private fun videoOrSeek(id: String, t: String?, currentId: String?): LinkTarget {
    if (id != currentId) return LinkTarget.Video(id)
    return LinkTarget.Seek(parseStartTime(t) ?: 0L)
}

/** YouTube's t= / start=: "70", "70s", "1m10s", "1h2m3s" → ms; null if unreadable. */
internal fun parseStartTime(t: String?): Long? {
    if (t.isNullOrBlank()) return null
    t.toLongOrNull()?.let { return it * 1000 }
    val m = Regex("""^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$""").matchEntire(t) ?: return null
    if (m.value.isEmpty()) return null
    val (h, min, s) = m.destructured
    return ((h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000
}

private fun isYouTubeHost(host: String?): Boolean {
    val h = host?.lowercase() ?: return false
    return h == "youtube.com" || h.endsWith(".youtube.com")
}

private fun queryParam(rawQuery: String?, name: String): String? =
    rawQuery?.split('&')?.firstNotNullOfOrNull { p ->
        val k = p.substringBefore('=')
        if (k == name) runCatching { URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }.getOrNull() else null
    }

/** Runs a search from anywhere (a #hashtag tapped in a description); MainActivity provides it. */
val LocalSearchOpener = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Comment text with its timestamps and web addresses as links (GitHub #66): "4:21" seeks video [videoId] (a
 * watch link with t=, so [linkTarget] makes it a seek on the open video). YouTube's comment data carries the
 * text alone here, so timestamps are found by their shape.
 */
fun commentLinks(text: String, videoId: String?): Description {
    val stamps = if (videoId == null) emptyList() else Timestamp.findAll(text).mapNotNull { m ->
        val (h, mm, ss) = m.destructured
        val minutes = mm.toInt()
        val seconds = ss.toInt()
        if (seconds > 59 || (h.isNotEmpty() && minutes > 59)) return@mapNotNull null
        val total = (h.toIntOrNull() ?: 0) * 3600 + minutes * 60 + seconds
        DescriptionLink(m.range.first, m.range.last + 1, "https://www.youtube.com/watch?v=$videoId&t=${total}s")
    }.toList()
    return Description.withBareLinks(text, stamps)
}

/** 4:21, 1:02:33 — not inside a longer run of digits and colons (a score "3:2:1", a time "12:30:45:10"). */
private val Timestamp = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,3}):(\d{2})(?![\d:])""")

/** What a tapped link does, where one can be tapped (the open player provides it; elsewhere text stays plain). */
val LocalLinkOpener = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * A link tapped in the player's description or comments: a timestamp of this video seeks, another video plays
 * here, a channel opens its page, a hashtag searches, the rest goes to the browser.
 */
@androidx.compose.runtime.Composable
fun rememberLinkOpener(state: PlayerState): (String) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val openChannel = LocalChannelOpener.current
    val openSearch = LocalSearchOpener.current
    return androidx.compose.runtime.remember(state, context, openChannel, openSearch) {
        { url ->
            when (val target = linkTarget(url, state.current?.id)) {
                is LinkTarget.Seek -> state.seekTo(target.ms)
                is LinkTarget.Video -> state.open(
                    VideoItem(
                        id = target.id, title = "", channel = "", channelUrl = null,
                        thumbnail = "https://i.ytimg.com/vi/${target.id}/hqdefault.jpg", avatar = null,
                        durationSec = 0, views = -1, uploaded = null,
                    ),
                    startAtMs = linkStartMs(url),
                )
                is LinkTarget.Channel -> openChannel(target.url)
                is LinkTarget.Search -> openSearch(target.query)
                is LinkTarget.Web -> try {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(target.url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    state.actionMessage = str(R.string.settings_no_app)
                }
            }
        }
    }
}

/** [description]'s text with every link in the theme's primary, whole, and tappable through [onLink]; plain without it. */
@androidx.compose.runtime.Composable
fun LinkedText(
    description: Description,
    onLink: ((String) -> Unit)?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
) {
    val c = androidx.compose.material3.MaterialTheme.colorScheme
    val linkStyles = androidx.compose.ui.text.TextLinkStyles(
        style = androidx.compose.ui.text.SpanStyle(color = c.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        pressedStyle = androidx.compose.ui.text.SpanStyle(color = c.primary.copy(alpha = 0.55f)),
    )
    val open = androidx.compose.runtime.rememberUpdatedState(onLink)
    val linked = onLink != null
    val text = androidx.compose.runtime.remember(description, linkStyles, linked) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(description.text)
            if (linked) for (link in description.links) {
                addLink(androidx.compose.ui.text.LinkAnnotation.Clickable(link.url, linkStyles) { open.value?.invoke(link.url) }, link.start, link.end)
            }
        }
    }
    androidx.compose.material3.Text(text, modifier = modifier, color = color, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = fontWeight)
}

/** The open player's description, its links tappable ([rememberLinkOpener]). */
@androidx.compose.runtime.Composable
fun DescriptionText(description: Description, state: PlayerState, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    LinkedText(
        description, rememberLinkOpener(state), modifier,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
    )
}
