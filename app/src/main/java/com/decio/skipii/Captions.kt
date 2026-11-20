package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Captions (Derek, 2026-11-01; docs/design/captions.html). YouTube lists every track a video has with the
// player answer (NewPipe's `subtitles`): the creator's, and "asr" — auto-generated, in the language spoken.
// The files (srv3 XML) need no sign-in or PO token. Auto-generated ones carry a time per word (the transcript
// lights words with it); on the video they show two whole lines at a time, as YouTube does (`captionPages`). YouTube's own auto-translate (`tlang`) is refused (HTTP 429,
// probed 2026-11-01), so translations are made on the phone (ML Kit, a model per language).

/** One caption track YouTube has for a video. [language] is its tag ("en", "pt-BR"); [auto] = auto-generated. */
@Immutable
data class CaptionTrack(val language: String, val auto: Boolean, val url: String) {
    /** The bare language ("pt" for "pt-BR"). */
    val base: String get() = Locale.forLanguageTag(language).language.ifBlank { language }
}

/** A word of an auto-generated line, [offsetMs] after the line starts. [text] keeps its leading space. */
@Immutable
data class CaptionWord(val offsetMs: Long, val text: String)

/** One timed caption: a line (or two) of the creator's, or a line of an auto-generated track with its [words]. */
@Immutable
data class CaptionCue(val startMs: Long, val endMs: Long, val text: String, val words: List<CaptionWord>? = null) {
    /** "[Music]", "[hinge creaks]": a sound, not speech (drawn in italics). */
    val sound: Boolean get() = text.startsWith('[') && text.endsWith(']')
}

/** What the captions show: [track], or [track] translated on the phone into [translateTo] (an ML Kit code). */
@Immutable
data class CaptionChoice(val track: CaptionTrack, val translateTo: String? = null) {
    /** The language read on screen. */
    val language: String get() = translateTo ?: track.base
    val label: String get() = CaptionNames.language(language)
}

/** A line on screen right now. */
@Immutable
data class CaptionLine(val text: String, val sound: Boolean)

/** The creator's lines showing at [t]: every cue running then (three at most). [cues] sorted by start. */
fun captionLinesAt(cues: List<CaptionCue>, t: Long): List<CaptionLine> {
    val last = cueIndexAt(cues, t)
    if (last < 0) return emptyList()
    val active = ArrayList<CaptionCue>(3)
    // Cues rarely overlap, and only a few deep.
    var i = last
    while (i >= 0 && i > last - 8) {
        val cue = cues[i]
        if (cue.endMs > t) active.add(0, cue)
        i--
    }
    return active.takeLast(3).map { CaptionLine(it.text, it.sound) }
}

/** Lines of an auto-generated track shown together, from [startMs] to [endMs]. */
@Immutable
data class CaptionPage(val startMs: Long, val endMs: Long, val lines: List<CaptionLine>, val cues: List<CaptionCue> = emptyList())

/**
 * An auto-generated track as YouTube shows it (Derek, 0.5.77): two lines at a time, both at once, then a hard cut
 * to the next two as the first word of the next line is said. No roll, no word-by-word (0.5.75's word by word and
 * 0.5.76's roll-up were both hard to follow). Lines pair up in order; a pause over [PairGapMs] or a sound ("[Music]")
 * ends a pair early. A page stays until the next one starts, or until its own lines are over. [cues] sorted by start.
 */
fun captionPages(cues: List<CaptionCue>): List<CaptionPage> {
    val groups = ArrayList<List<CaptionCue>>()
    var i = 0
    while (i < cues.size) {
        val first = cues[i]
        val next = cues.getOrNull(i + 1)
        if (next != null && !first.sound && !next.sound && next.startMs - first.endMs <= PairGapMs) {
            groups += listOf(first, next)
            i += 2
        } else {
            groups += listOf(first)
            i++
        }
    }
    return groups.mapIndexed { k, group ->
        val next = groups.getOrNull(k + 1)?.first()?.startMs ?: Long.MAX_VALUE
        CaptionPage(group.first().startMs, minOf(group.maxOf { it.endMs }, next), group.map { CaptionLine(it.text, it.sound) }, group)
    }
}

/**
 * The page's lines showing at [t], or none between pages. [wordByWord] (Settings › Captions, GitHub #87): each line
 * shows only the words said so far, as YouTube's app does; the page still cuts to the next pair whole.
 */
fun captionPageAt(pages: List<CaptionPage>, t: Long, wordByWord: Boolean = false): List<CaptionLine> {
    var lo = 0
    var hi = pages.size - 1
    var last = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (pages[mid].startMs <= t) { last = mid; lo = mid + 1 } else hi = mid - 1
    }
    val page = pages.getOrNull(last) ?: return emptyList()
    if (t >= page.endMs) return emptyList()
    if (!wordByWord || page.cues.isEmpty()) return page.lines
    return page.cues.mapNotNull { cue ->
        val words = cue.words
        when {
            t < cue.startMs -> null
            cue.sound || words.isNullOrEmpty() -> CaptionLine(cue.text, cue.sound)
            else -> words.filter { cue.startMs + it.offsetMs <= t }.joinToString("") { it.text }.trim()
                .ifEmpty { null }?.let { CaptionLine(it, false) }
        }
    }
}

/** Two auto-generated lines further apart than this aren't shown as one page. */
private const val PairGapMs = 1_000L

/** The index of the cue playing at [t] (the last one started), or -1 before the first. */
fun cueIndexAt(cues: List<CaptionCue>, t: Long): Int {
    var lo = 0
    var hi = cues.size - 1
    var last = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (cues[mid].startMs <= t) { last = mid; lo = mid + 1 } else hi = mid - 1
    }
    return last
}

/**
 * YouTube's srv3 caption format: `<p t="start ms" d="duration ms">text</p>` in a `<body>`. Auto-generated
 * lines hold their words as `<s t="offset ms"> word</s>`; blank `<p a="1">` lines only mark line breaks.
 */
object Srv3 {
    private val paragraph = Regex("""<p\b([^>]*?)(?:/>|>(.*?)</p>)""", RegexOption.DOT_MATCHES_ALL)
    private val segment = Regex("""<s\b([^>]*?)(?:/>|>(.*?)</s>)""", RegexOption.DOT_MATCHES_ALL)
    private val attribute = Regex("""([\w:-]+)="([^"]*)"""")
    private val lineBreak = Regex("""<br\s*/?>""")
    private val tag = Regex("<[^>]+>")
    private val entity = Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);""")

    /** [timedWords]: keep each `<s>`'s timing (auto-generated tracks); otherwise just the text. */
    fun parse(xml: String, timedWords: Boolean): List<CaptionCue> {
        val body = xml.substringAfter("<body>", "").substringBefore("</body>")
        return paragraph.findAll(body).mapNotNull { m ->
            val attrs = attributes(m.groupValues[1])
            val start = attrs["t"]?.toLongOrNull() ?: return@mapNotNull null
            val duration = attrs["d"]?.toLongOrNull() ?: return@mapNotNull null
            if (duration <= 0) return@mapNotNull null
            val inner = m.groupValues[2]
            val words = if (timedWords && inner.contains("<s")) {
                segment.findAll(inner).map { s ->
                    CaptionWord(attributes(s.groupValues[1])["t"]?.toLongOrNull() ?: 0L, unescape(tag.replace(s.groupValues[2], "")))
                }.filter { it.text.isNotBlank() }.toList().takeIf { it.isNotEmpty() }
            } else null
            val text = words?.joinToString("") { it.text }?.trim()
                ?: unescape(tag.replace(lineBreak.replace(inner, "\n"), "")).lines().joinToString("\n") { it.trim() }.trim()
            if (text.isBlank()) null else CaptionCue(start, start + duration, text, words)
        }.sortedBy { it.startMs }.toList()
    }

    private fun attributes(s: String): Map<String, String> = attribute.findAll(s).associate { it.groupValues[1] to it.groupValues[2] }

    private fun unescape(s: String): String = entity.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> (if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull())
                ?.let { String(Character.toChars(it)) } ?: m.value
        }
    }
}

/** Language names in the app's language: "German", "Alemán", "ドイツ語". */
object CaptionNames {
    fun language(tag: String): String {
        val app = AppLanguage.locale
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(app).ifBlank { tag }
        return name.replaceFirstChar { it.titlecase(app) }
    }

    /** With the country when it tells two tracks apart: "Portuguese (Brazil)". */
    fun full(tag: String): String {
        val app = AppLanguage.locale
        val name = Locale.forLanguageTag(tag).getDisplayName(app).ifBlank { tag }
        return name.replaceFirstChar { it.titlecase(app) }
    }
}

/** How a track is named in a list: its language, plus the country when another track shares the language. */
fun CaptionTrack.title(all: List<CaptionTrack>): String =
    if (all.count { it.base == base && it.auto == auto } > 1) CaptionNames.full(language) else CaptionNames.language(language)

/** The captions' settings (account sheet), and the language last picked (YouTube remembers it too). */
object CaptionSettings {
    enum class Show { Never, Always, Foreign }
    enum class Look(@StringRes val labelRes: Int) {
        Skipii(R.string.captions_look_skipii), Classic(R.string.captions_look_classic),
        Outline(R.string.captions_look_outline), System(R.string.captions_look_system),
    }
    /** Each size and its label (a string resource). */
    val sizes = listOf(
        0.85f to R.string.captions_size_small, 1f to R.string.captions_size_medium,
        1.22f to R.string.captions_size_large, 1.5f to R.string.captions_size_huge,
        1.85f to R.string.captions_size_huger,
    )

    private const val KEY_SHOW = "show"
    private const val KEY_LOOK = "look"
    private const val KEY_SIZE = "size"
    private const val KEY_WORD_BY_WORD = "word_by_word"
    private const val KEY_LANGUAGE = "language"
    private var prefs: SharedPreferences? = null

    var show by mutableStateOf(Show.Foreign)
        private set
    var look by mutableStateOf(Look.Skipii)
        private set
    var size by mutableFloatStateOf(1f)
        private set
    /** Auto-generated captions appear word by word as they're said ([captionPageAt]); off = two whole lines. */
    var wordByWord by mutableStateOf(false)
        private set
    /** The language last picked, if one was. */
    private var pickedLanguage by mutableStateOf<String?>(null)
    /** The language captions are shown in when the video has it (or it can be translated into): the last picked, else the app's. */
    val language: String get() = pickedLanguage ?: appLanguage

    /** The app's language (the one picked in Skipii, else the phone's): "When it isn't in English" compares the video's with this. */
    val appLanguage: String get() = AppLanguage.locale.language.ifBlank { "en" }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("captions", Context.MODE_PRIVATE)
        show = prefs?.getString(KEY_SHOW, null)?.let { s -> Show.entries.firstOrNull { it.name == s } } ?: Show.Foreign
        look = prefs?.getString(KEY_LOOK, null)?.let { s -> Look.entries.firstOrNull { it.name == s } } ?: Look.Skipii
        size = prefs?.getFloat(KEY_SIZE, 1f) ?: 1f
        wordByWord = prefs?.getBoolean(KEY_WORD_BY_WORD, false) == true
        pickedLanguage = prefs?.getString(KEY_LANGUAGE, null)
    }

    fun chooseShow(value: Show) { show = value; prefs?.edit()?.putString(KEY_SHOW, value.name)?.apply() }
    fun chooseLook(value: Look) { look = value; prefs?.edit()?.putString(KEY_LOOK, value.name)?.apply() }
    fun chooseWordByWord(value: Boolean) { wordByWord = value; prefs?.edit()?.putBoolean(KEY_WORD_BY_WORD, value)?.apply() }
    fun chooseSize(value: Float) { size = value; prefs?.edit()?.putFloat(KEY_SIZE, value)?.apply() }
    fun rememberLanguage(value: String) { pickedLanguage = value; prefs?.edit()?.putString(KEY_LANGUAGE, value)?.apply() }
}

/** Caption files, fetched once per track and kept for the last few videos. */
object CaptionFiles {
    private val cache = LruCache<String, List<CaptionCue>>(12)

    suspend fun load(track: CaptionTrack): List<CaptionCue> = cache.get(track.url) ?: withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder().url(track.url).header("User-Agent", Net.USER_AGENT).build()
        // A link YouTube wants a PO token for carries exp=xpe (the web page's do, the mobile clients' didn't on
        // 2026-11-17); an expired link (~7 h) answers 404; both a token refusal and a broken link answer an empty 200.
        val xpe = "exp=xpe" in track.url
        val body = Net.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("caption file HTTP ${response.code}${if (xpe) ", exp=xpe" else ""}")
            response.body?.string().orEmpty()
        }
        if (body.isBlank()) throw IOException("empty caption file${if (xpe) " (exp=xpe: wants a PO token)" else ""}")
        Srv3.parse(body, timedWords = track.auto).also {
            Log.i(TAG, "captions: ${track.language}${if (track.auto) " (auto)" else ""} ${it.size} lines")
            cache.put(track.url, it)
        }
    }
}

/**
 * Caption links asked for again (GitHub #64): when a track's file comes back empty or refused, a fresh player
 * answer from VISIONOS (PlayerFallback) lists the tracks with new links. Once per video.
 */
object CaptionRescue {
    suspend fun tracks(videoId: String): List<CaptionTrack> = withContext(Dispatchers.IO) {
        val answer = PlayerFallback.freshAnswer(videoId)
        parseTracks(answer).also { Log.i(TAG, "captions: fresh links for $videoId, ${it.size} tracks") }
    }

    /** A player answer's caption tracks, each link asking for srv3. */
    internal fun parseTracks(answer: org.json.JSONObject): List<CaptionTrack> {
        val list = answer.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            val url = t.optString("baseUrl").ifBlank { null } ?: return@mapNotNull null
            val bare = url.replace(Regex("&fmt=[^&]*"), "")
            CaptionTrack(t.optString("languageCode"), t.optString("kind") == "asr", "$bare&fmt=srv3")
        }
    }
}

/**
 * Translation on the phone (ML Kit): a model per language (about 30 MB), downloaded once when it's first
 * picked, offline after that. English is always there.
 */
object CaptionTranslator {
    private val scope: CoroutineScope = MainScope()

    /** Languages (ML Kit codes) whose model is on the phone. */
    var downloaded by mutableStateOf(setOf(TranslateLanguage.ENGLISH))
        private set
    /** Models being downloaded right now. */
    var downloading by mutableStateOf(emptySet<String>())
        private set

    /**
     * ML Kit's code for a language tag, or null if it can't translate that language. Java's old codes
     * (Indonesian "in", Hebrew "iw" — what Locale.language gives below Android 14) are mapped to ML Kit's.
     */
    fun code(tag: String): String? {
        val language = when (val l = Locale.forLanguageTag(tag).language) { "in" -> "id"; "iw" -> "he"; "ji" -> "yi"; else -> l }
        return TranslateLanguage.fromLanguageTag(language)
    }

    val all: List<String> get() = TranslateLanguage.getAllLanguages()

    fun ready(source: String, target: String) = source in downloaded && target in downloaded

    fun refresh() {
        scope.launch {
            runCatching { RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await() }
                .onSuccess { models -> downloaded = models.map { it.language }.toSet() + TranslateLanguage.ENGLISH }
                .onFailure { Log.w(TAG, "captions: couldn't list translation models", it) }
        }
    }

    /** Downloads what translating [source] into [target] needs (nothing if it's all here). */
    suspend fun prepare(source: String, target: String) {
        if (ready(source, target)) return
        val missing = setOf(source, target) - downloaded
        downloading = downloading + missing
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        try {
            Log.i(TAG, "captions: downloading translation models $missing")
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            downloaded = downloaded + setOf(source, target)
        } finally {
            downloading = downloading - missing
            translator.close()
        }
    }

    /** Translates each of [texts] (a caption line each) from [source] into [target], handing each over as it's done. */
    suspend fun translate(source: String, target: String, texts: List<Pair<Int, String>>, onLine: (Int, String) -> Unit) {
        val translator = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build())
        try {
            for ((index, text) in texts) onLine(index, translator.translate(text).await())
        } finally {
            translator.close()
        }
    }

    fun delete(language: String) {
        if (language == TranslateLanguage.ENGLISH) return
        scope.launch {
            runCatching { RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(language).build()).await() }
                .onFailure { Log.w(TAG, "captions: couldn't delete the $language model", it) }
            refresh()
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * The open video's captions: its tracks, what's chosen, whether it's on, and the chosen lines once loaded
 * (also while the transcript is open with captions off). [positionMs] is where playback is, so a
 * translation starts with the lines about to be said.
 */
@Stable
class CaptionsState(private val scope: CoroutineScope, private val positionMs: () -> Long) {
    /** The video these tracks are for (fresh links are asked for by id). */
    private var videoId: String? = null
    /** Fresh links were already asked for this video (CaptionRescue): never twice. */
    private var rescued = false
    var tracks by mutableStateOf<List<CaptionTrack>>(emptyList())
        private set
    var choice by mutableStateOf<CaptionChoice?>(null)
        private set
    var on by mutableStateOf(false)
        private set
    /** The chosen lines, sorted by start; null until loaded. A translation fills in as it goes. */
    var cues by mutableStateOf<List<CaptionCue>?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    /** Why the chosen lines couldn't be had, if they couldn't. */
    var error by mutableStateOf<String?>(null)
        private set
    private var transcriptOpen = false
    private var job: Job? = null
    private var loadedFor: CaptionChoice? = null

    /** The language spoken: what the auto-generated track is in. Unknown without one. */
    val videoLanguage: String? get() = tracks.firstOrNull { it.auto }?.base

    /** What a translation reads: the creator's track in the video's language, else the auto-generated one. */
    val translationSource: CaptionTrack?
        get() {
            val spoken = videoLanguage
            return tracks.firstOrNull { !it.auto && it.base == spoken } ?: tracks.firstOrNull { it.auto } ?: tracks.singleOrNull()
        }

    fun clear() {
        job?.cancel()
        job = null
        tracks = emptyList()
        choice = null
        on = false
        cues = null
        loading = false
        error = null
        loadedFor = null
    }

    /** A video's tracks are in: choose and turn on as the settings say. */
    fun useTracks(list: List<CaptionTrack>, videoId: String) {
        clear()
        this.videoId = videoId
        rescued = false
        Log.i(TAG, "captions: ${list.size} tracks ${list.map { it.language + if (it.auto) " (auto)" else "" }}")
        // The language last picked, then the app's, then the rest by name; auto-generated after the creator's in each.
        tracks = list.sortedWith(
            compareBy<CaptionTrack>(
                { it.base != CaptionSettings.language }, { it.base != CaptionSettings.appLanguage },
                { CaptionNames.full(it.language) }, { it.auto },
            ),
        )
        if (tracks.isEmpty()) return
        val best = bestFor(CaptionSettings.language)
        val spoken = videoLanguage
        val foreign = spoken != null && spoken != CaptionSettings.appLanguage
        on = when (CaptionSettings.show) {
            CaptionSettings.Show.Never -> false
            CaptionSettings.Show.Always -> true
            CaptionSettings.Show.Foreign -> foreign && best != null
        }
        choice = best ?: translationSource?.let { CaptionChoice(it) } ?: tracks.first().let { CaptionChoice(it) }
        reload()
    }

    /** The best way to read [language]: the creator's track, the auto-generated one, or a translation that's ready. */
    fun bestFor(language: String): CaptionChoice? =
        tracks.firstOrNull { !it.auto && it.base == language }?.let { CaptionChoice(it) }
            ?: tracks.firstOrNull { it.auto && it.base == language }?.let { CaptionChoice(it) }
            ?: translationInto(language)?.takeIf { c -> CaptionTranslator.ready(CaptionTranslator.code(c.track.base) ?: "", c.translateTo ?: "") }

    /** [translationSource] translated into [language], if ML Kit has both languages. */
    fun translationInto(language: String): CaptionChoice? {
        val source = translationSource ?: return null
        val from = CaptionTranslator.code(source.base) ?: return null
        val to = CaptionTranslator.code(language) ?: return null
        if (from == to) return null
        return CaptionChoice(source, to)
    }

    fun pick(c: CaptionChoice) {
        choice = c
        on = true
        CaptionSettings.rememberLanguage(c.language)
        reload()
    }

    fun turn(value: Boolean) {
        if (value && choice == null) return
        on = value
        reload()
    }

    /** The transcript needs the lines even with captions off. */
    fun setTranscript(open: Boolean) {
        transcriptOpen = open
        reload()
    }

    /** Loads the chosen lines when something shows them; tries again after a failure on the next ask. */
    fun reload() {
        val c = choice ?: return
        if (!on && !transcriptOpen) return
        if (c == loadedFor) return
        job?.cancel()
        loadedFor = c
        cues = null
        error = null
        loading = true
        job = scope.launch {
            var now = c
            var loaded = false
            try {
                val source = try {
                    CaptionFiles.load(c.track)
                } catch (e: IOException) {
                    // The link was refused or has expired: once per video, ask for fresh links and try again.
                    val fresh = rescue(c.track) ?: throw e
                    Log.w(TAG, "captions: ${c.track.language} failed (${e.message}); trying fresh links")
                    now = c.copy(track = fresh)
                    if (loadedFor == c) { loadedFor = now; choice = now }
                    CaptionFiles.load(fresh)
                }
                loaded = true
                val target = now.translateTo
                if (target == null) {
                    cues = source
                } else {
                    val from = CaptionTranslator.code(now.track.base) ?: throw IOException("can't translate ${now.track.base}")
                    CaptionTranslator.prepare(from, target)
                    translate(source, from, target)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "captions: ${now.track.language} → ${now.translateTo ?: "-"} failed", e)
                if (loadedFor == now) {
                    error = when {
                        now.translateTo != null && now.translateTo !in CaptionTranslator.downloaded -> str(R.string.captions_error_download, now.label)
                        // The file was fine; the phone's translation wasn't (it used to say "Couldn't load", as if YouTube had refused).
                        loaded -> str(R.string.captions_error_translate, now.label)
                        else -> str(R.string.captions_error_load)
                    }
                    loadedFor = null
                }
            } finally {
                if (loadedFor == now || loadedFor == null) loading = false
            }
        }
    }

    /** [track] with fresh links (CaptionRescue), the track list updated with them; null if none came back. */
    private suspend fun rescue(track: CaptionTrack): CaptionTrack? {
        val id = videoId ?: return null
        if (rescued) return null
        rescued = true
        val fresh = runCatching { CaptionRescue.tracks(id) }
            .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "captions: fresh links for $id failed", it) }
            .getOrNull().orEmpty()
        if (fresh.isEmpty() || videoId != id) return null
        val match = fresh.firstOrNull { it.language == track.language && it.auto == track.auto }
            ?: fresh.firstOrNull { it.base == track.base && it.auto == track.auto }
            ?: return null
        // Every track gets its fresh link (the next pick would fail the same way), kept in the same order.
        tracks = tracks.map { old -> fresh.firstOrNull { it.language == old.language && it.auto == old.auto } ?: old }
        return match
    }

    /**
     * Translates sentence by sentence (a line is often half of one, and translated alone it reads badly), then
     * shares each translation out over its lines by length, so the lines keep their times. From the sentence
     * playing now onward, then the ones before; shown as they come.
     */
    private suspend fun translate(source: List<CaptionCue>, from: String, to: String) {
        val sentences = sentencesOf(source)
        val done = arrayOfNulls<String>(source.size)
        val now = cueIndexAt(source, positionMs()).coerceAtLeast(0)
        val first = sentences.indexOfFirst { now in it }.coerceAtLeast(0)
        val order = (first until sentences.size) + (0 until first)
        var since = 0
        fun publish() {
            cues = source.indices.mapNotNull { i -> done[i]?.takeIf { it.isNotBlank() }?.let { source[i].copy(text = it, words = null) } }
        }
        CaptionTranslator.translate(from, to, order.map { s -> s to sentences[s].joinToString(" ") { source[it].text } }) { s, text ->
            val lines = sentences[s]
            shareOut(text, lines.map { source[it].text.length }).forEachIndexed { k, part -> done[lines.first + k] = part }
            // The first few at once (they're on screen next), then in batches.
            if (++since >= 8 || s in first..first + 1) { since = 0; publish() }
        }
        publish()
    }

    /** Lines grouped into sentences: up to a line ending . ? ! or …, a pause over 1.5 s, or 5 lines; sounds stand alone. */
    private fun sentencesOf(source: List<CaptionCue>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var from = 0
        for (i in source.indices) {
            val cue = source[i]
            val next = source.getOrNull(i + 1)
            val ends = cue.sound || next == null || next.sound || i - from >= 4 ||
                next.startMs - cue.endMs > 1_500 || cue.text.trimEnd('"', '\'', ')', '”', '’').lastOrNull()?.let { it in ".?!…。？！" } == true
            if (ends) { out += from..i; from = i + 1 }
        }
        return out
    }

    /** [text] cut at spaces into as many parts as [lengths], each about its share of the length. */
    private fun shareOut(text: String, lengths: List<Int>): List<String> {
        if (lengths.size <= 1) return listOf(text)
        val words = text.split(' ').filter { it.isNotEmpty() }
        val total = lengths.sum().coerceAtLeast(1)
        val parts = ArrayList<String>(lengths.size)
        var w = 0
        var share = 0
        for ((k, length) in lengths.withIndex()) {
            share += length
            val end = if (k == lengths.lastIndex) words.size
            else (words.size * share.toFloat() / total).toInt().coerceIn(w, words.size)
            parts += words.subList(w, end).joinToString(" ")
            w = end
        }
        return parts
    }
}

private const val TAG = "Skipii"
