package com.decio.skipii

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * One audio track YouTube offers for a video (the original spoken track, a creator dub, a YouTube
 * auto-dub, or audio description). Single-track videos have none exposed in the UI; only videos
 * with more than one distinct track offer the picker.
 */
@Immutable
data class AudioTrack(
    /** YouTube's track id (e.g. "en-US.4", "es.3", "es-419.3"), or "" when YouTube omits one. */
    val id: String,
    /** BCP-47 language tag (e.g. "en-US", "es-419", "es"), or "" if unknown. */
    val language: String,
    /** Fallback name from YouTube, cleaned of redundant "original" / "(auto-dubbed)" suffixes. */
    val rawName: String,
    val type: AudioTrackType?,
    val autoDubbed: Boolean = false,
) {
    val isOriginal: Boolean get() = type == null || type == AudioTrackType.ORIGINAL
    val isDescriptive: Boolean get() = type == AudioTrackType.DESCRIPTIVE
    val isSecondary: Boolean get() = type == AudioTrackType.SECONDARY

    /** Normalized primary language subtag ("es" for "es-419", "id" for legacy "in", "he" for "iw"). */
    val baseLanguage: String get() = normalizeBaseLanguage(language)

    /** Short uppercase badge for compact pills (e.g. "ES", "PT", "AD"). */
    val shortCode: String
        get() = when {
            isDescriptive -> "AD"
            baseLanguage.isNotBlank() -> baseLanguage.uppercase(Locale.US).take(3)
            else -> rawName.take(2).uppercase(Locale.US)
        }

    /**
     * Display name in [locale]: localized language name, adding the region or script when another
     * track in [all] shares the same base language (e.g. "Spanish (Latin America)", "Chinese (Simplified)").
     */
    fun title(all: List<AudioTrack>, locale: Locale = currentAppLocale()): String {
        if (language.isBlank()) return rawName.ifBlank { id }
        val tagLocale = Locale.forLanguageTag(language)
        val sameBase = all.count { it.baseLanguage == baseLanguage && it.isDescriptive == isDescriptive } > 1
        val needsFull = sameBase || tagLocale.script.isNotBlank()
        val localized = (if (needsFull) tagLocale.getDisplayName(locale) else tagLocale.getDisplayLanguage(locale))
            .ifBlank { rawName }
            .ifBlank { language }
        return localized.replaceFirstChar { it.titlecase(locale) }
    }

    /** Short label for the compact tools circle (bare language name, never region-wrapped). */
    fun shortLabel(locale: Locale = currentAppLocale()): String {
        if (language.isBlank()) return rawName.ifBlank { id }
        val tagLocale = Locale.forLanguageTag(language)
        val localized = tagLocale.getDisplayLanguage(locale).ifBlank { rawName }.ifBlank { language }
        return localized.replaceFirstChar { it.titlecase(locale) }
    }
}

/**
 * Default audio-track behavior for multi-audio videos (Settings › Captions & dubbing).
 *
 * Defaults to [Mode.Original] so normal English videos — and every other video — always start in
 * their original spoken language unless the user picks a dub in the player or chooses a specific
 * language in Settings.
 */
object AudioTrackPreference {
    enum class Mode { Original, Custom }

    private const val KEY_MODE = "audio_track_mode"
    private const val KEY_AUTO_DUB = "audio_allow_auto_dub"
    private const val KEY_LAST_LANG = "audio_last_language"
    private const val KEY_PREFERRED_LANG = "audio_preferred_language"
    private var prefs: SharedPreferences? = null

    var mode by mutableStateOf(Mode.Original)
        private set

    /** Whether automatic selection ([Mode.Custom]) may pick YouTube AI auto-dubs. */
    var allowAutoDub by mutableStateOf(false)
        private set

    /** Language tag last picked manually in the player (used only to pin it near the top of the player picker). */
    var lastPickedLanguage by mutableStateOf<String?>(null)
        private set

    /** User-chosen language tag for [Mode.Custom] (e.g. "es", "es-419", "ja"), or null if none chosen. */
    var preferredLanguage by mutableStateOf<String?>(null)
        private set

    /** Per-video manual overrides (videoId -> AudioTrack.id); never leaks to the next video. */
    private val videoOverrides = ConcurrentHashMap<String, String>()

    /** Bumped whenever a per-video override changes so Compose observers update immediately. */
    var overrideVersion by mutableIntStateOf(0)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        val savedMode = prefs?.getString(KEY_MODE, null)?.let { s -> Mode.entries.firstOrNull { it.name == s } } ?: Mode.Original
        allowAutoDub = prefs?.getBoolean(KEY_AUTO_DUB, false) == true
        lastPickedLanguage = prefs?.getString(KEY_LAST_LANG, null)?.ifBlank { null }
        preferredLanguage = prefs?.getString(KEY_PREFERRED_LANG, null)?.ifBlank { null }
        mode = if (savedMode == Mode.Custom && preferredLanguage == null) Mode.Original else savedMode
    }

    fun chooseMode(value: Mode) {
        mode = value
        prefs?.edit()?.putString(KEY_MODE, value.name)?.apply()
        clearOverrides()
    }

    fun choosePreferredLanguage(tag: String) {
        val cleaned = tag.trim().ifBlank { return }
        preferredLanguage = cleaned
        mode = Mode.Custom
        prefs?.edit()
            ?.putString(KEY_PREFERRED_LANG, cleaned)
            ?.putString(KEY_MODE, Mode.Custom.name)
            ?.apply()
        clearOverrides()
    }

    fun chooseAllowAutoDub(on: Boolean) {
        allowAutoDub = on
        prefs?.edit()?.putBoolean(KEY_AUTO_DUB, on)?.apply()
        clearOverrides()
    }

    /** User manually picked [track] for [videoId]. */
    fun setForVideo(videoId: String, track: AudioTrack) {
        videoOverrides[videoId] = track.id
        overrideVersion++
        if (!track.isOriginal && track.language.isNotBlank()) {
            lastPickedLanguage = track.language
            prefs?.edit()?.putString(KEY_LAST_LANG, track.language)?.apply()
        }
    }

    fun overrideFor(videoId: String): String? = videoOverrides[videoId]

    fun clearOverrides() {
        if (videoOverrides.isNotEmpty()) {
            videoOverrides.clear()
            overrideVersion++
        }
    }

    /**
     * Resolves which track id to play for [tracks]. Pure function for easy testing:
     * - Single-track or empty lists return the lone track (normal English videos are untouched).
     * - A per-video override always wins for that video.
     * - [Mode.Original] always returns the original track.
     * - [Mode.Custom] dubs to [preferredLanguage] when set and available, never overrides a video
     *   whose original track already matches that language, and skips auto-dubs unless [allowAutoDub] is on.
     */
    fun resolveTrackId(
        tracks: List<AudioTrack>,
        overrideId: String? = null,
        mode: Mode = this.mode,
        allowAutoDub: Boolean = this.allowAutoDub,
        preferredLanguage: String? = this.preferredLanguage,
    ): String? {
        if (tracks.isEmpty()) return null
        val original = tracks.firstOrNull { it.isOriginal } ?: tracks.first()
        if (tracks.size == 1) return original.id
        if (overrideId != null && tracks.any { it.id == overrideId }) return overrideId
        if (mode == Mode.Original) return original.id

        val targetTag = preferredLanguage ?: return original.id
        val targetBase = normalizeBaseLanguage(targetTag)
        if (targetBase.isBlank()) return original.id

        // If the video's original spoken language is already the desired language, keep Original.
        if (original.baseLanguage == targetBase) return original.id

        val eligible = tracks.filter { !it.isOriginal && !it.isDescriptive && !it.isSecondary && (allowAutoDub || !it.autoDubbed) }
        val exact = eligible
            .filter { it.language.equals(targetTag, ignoreCase = true) }
            .minByOrNull { if (it.autoDubbed) 1 else 0 }
        if (exact != null) return exact.id

        val byBase = eligible
            .filter { it.baseLanguage == targetBase }
            .minByOrNull { if (it.autoDubbed) 1 else 0 }
        return byBase?.id ?: original.id
    }
}

/** Extracts, deduplicates, and sorts audio tracks from NewPipe's [AudioStream] list. */
object AudioTracks {
    private val TRAILING_ORIGINAL = Regex("\\s+original\\s*$", RegexOption.IGNORE_CASE)
    private val TRAILING_AUTO_DUB = Regex("\\s*\\(auto[- ]?dubbed\\)\\s*$", RegexOption.IGNORE_CASE)

    /** True when the YouTube stream URL or display name marks this track as an AI auto-dub. */
    fun isAutoDubbed(url: String?, rawName: String?): Boolean {
        val u = url.orEmpty()
        if (u.contains("acont=dubbed-auto", ignoreCase = true) || u.contains("acont%3Ddubbed-auto", ignoreCase = true)) {
            return true
        }
        val n = rawName.orEmpty()
        return n.contains("auto-dubbed", ignoreCase = true) || n.contains("auto dubbed", ignoreCase = true)
    }

    /** True when the stream carries YouTube's dynamic-range-compressed variant (prefer standard). */
    fun isDrc(url: String?): Boolean {
        val u = url.orEmpty()
        return u.contains("drc=1", ignoreCase = true) || u.contains("drc%3D1", ignoreCase = true)
    }

    /** Cleans YouTube's redundant suffix ("English (US) original" → "English (US)") since we show our own tag. */
    fun cleanRawName(raw: String?): String = raw.orEmpty()
        .replace(TRAILING_ORIGINAL, "")
        .replace(TRAILING_AUTO_DUB, "")
        .trim()

    /** Common YouTube dubbed audio languages (BCP-47 tags) offered in Settings › Playback › Dub language. */
    val selectableLanguages: List<String> = listOf(
        "ar", "bn", "bg", "ca", "zh-Hans", "zh-Hant", "hr", "cs", "da", "nl",
        "en", "et", "fil", "fi", "fr", "de", "el", "gu", "he", "hi",
        "hu", "id", "it", "ja", "kn", "ko", "lv", "lt", "ms", "ml",
        "mr", "no", "fa", "pl", "pt", "pt-BR", "pa", "ro", "ru", "sr",
        "sk", "es", "es-419", "sv", "ta", "te", "th", "tr", "uk", "ur",
        "vi",
    )

    /** Localized display name for a dub language tag (e.g. "Spanish (Latin America)", "Japanese"). */
    fun displayLanguageName(tag: String, locale: Locale = currentAppLocale()): String {
        if (tag.isBlank()) return ""
        val tagLocale = Locale.forLanguageTag(tag)
        val hasQualifier = tagLocale.country.isNotBlank() || tagLocale.script.isNotBlank()
        val name = (if (hasQualifier) tagLocale.getDisplayName(locale) else tagLocale.getDisplayLanguage(locale))
            .ifBlank { tag }
        return name.replaceFirstChar { it.titlecase(locale) }
    }

    /** Native name of a dub language tag (e.g. "Español (Latinoamérica)", "日本語"). */
    fun nativeLanguageName(tag: String): String {
        if (tag.isBlank()) return ""
        val tagLocale = Locale.forLanguageTag(tag)
        return displayLanguageName(tag, tagLocale)
    }

    /** All distinct playable audio tracks offered by [info], sorted for the picker. Empty if single-track. */
    fun from(
        info: StreamInfo,
        appLocale: Locale = currentAppLocale(),
        lastPicked: String? = AudioTrackPreference.lastPickedLanguage,
        preferredLanguage: String? = AudioTrackPreference.preferredLanguage,
    ): List<AudioTrack> {
        val usable = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        if (usable.isEmpty()) return emptyList()
        val grouped = usable.groupBy { it.audioTrackId.orEmpty() }
        if (grouped.size <= 1) return emptyList()
        val tracks = grouped.map { (id, streams) ->
            val rep = bestStream(streams)
            val lang = rep.audioLocale?.toLanguageTag()?.takeIf { it.isNotBlank() && it != "und" }
                ?: id.substringBefore('.').takeIf { it.isNotBlank() && it != id }
                ?: ""
            AudioTrack(
                id = id,
                language = lang,
                rawName = cleanRawName(rep.audioTrackName),
                type = rep.audioTrackType,
                autoDubbed = streams.any { isAutoDubbed(it.content, it.audioTrackName) },
            )
        }
        return sort(tracks, appLocale, lastPicked, preferredLanguage)
    }

    /**
     * Sorts tracks for pickers: Original first, then the user's chosen dub language, then the app's
     * language, then the last picked language, then creator dubs alphabetically, then auto-dubs
     * alphabetically, then descriptive / secondary last.
     */
    fun sort(
        tracks: List<AudioTrack>,
        appLocale: Locale = currentAppLocale(),
        lastPicked: String? = null,
        preferredLanguage: String? = null,
    ): List<AudioTrack> {
        val appBase = normalizeBaseLanguage(appLocale.toLanguageTag())
        val pickedBase = lastPicked?.let(::normalizeBaseLanguage)?.takeIf { it.isNotBlank() }
        val preferredBase = preferredLanguage?.let(::normalizeBaseLanguage)?.takeIf { it.isNotBlank() }
        return tracks.sortedWith(
            compareBy<AudioTrack>(
                { !it.isOriginal },
                { it.isDescriptive || it.isSecondary },
                { preferredBase == null || it.baseLanguage != preferredBase },
                { it.baseLanguage != appBase },
                { pickedBase == null || it.baseLanguage != pickedBase },
                { it.autoDubbed },
                { it.title(tracks, appLocale).lowercase(appLocale) },
            ),
        )
    }

    /** Best playable stream among streams for the same audio track: non-DRC, M4A first, highest bitrate. */
    fun bestStream(streams: List<AudioStream>): AudioStream = streams.sortedWith(
        compareBy<AudioStream> { if (isDrc(it.content)) 1 else 0 }
            .thenBy { if (it.format == MediaFormat.M4A) 0 else 1 }
            .thenByDescending { it.averageBitrate },
    ).first()
}

/** Safe lookup for [AppLanguage.locale] that falls back to [Locale.getDefault] in JVM unit tests. */
private fun currentAppLocale(): Locale = runCatching { AppLanguage.locale }.getOrElse { Locale.getDefault() }

/** Normalizes a BCP-47 language tag to its lowercase primary language subtag (mapping Java's legacy codes). */
internal fun normalizeBaseLanguage(tag: String): String {
    if (tag.isBlank()) return ""
    val primary = tag.substringBefore('-').substringBefore('_').lowercase(Locale.US)
    return when (primary) {
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        else -> primary
    }
}

/** Human-readable tag for a track row ("Original", "Dubbed", "Auto-dubbed", "Audio description"). */
internal fun AudioTrack.tagText(): String = when {
    isOriginal -> str(R.string.audio_track_original)
    isDescriptive -> str(R.string.audio_track_descriptive)
    isSecondary -> str(R.string.audio_track_secondary)
    autoDubbed -> str(R.string.audio_track_auto_dubbed)
    else -> str(R.string.audio_track_dubbed)
}
