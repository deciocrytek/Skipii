package com.decio.skipii

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList

/**
 * Search 2.0 (0.6.0; Derek approved section 3 of docs/design/skipii-2.html, keeping the five tabs as they are).
 * Results come from YouTube's own web search (InnerTube `search`, anonymous when signed out) instead of the
 * extractor's one page of videos: videos, channels and playlists in YouTube's order, page after page, with
 * YouTube's own filters. Suggestions while typing come from the extractor's suggestion list.
 */

/** One search result, in the order YouTube gave them. */
@Immutable
sealed interface SearchHit {
    val key: String

    @Immutable
    data class Video(val video: VideoItem) : SearchHit {
        override val key get() = "v-" + video.id
    }

    @Immutable
    /** [subscribers]: the count, or -1 when YouTube didn't say (shown through [subscribersLine], in the app's language). */
    data class Channel(val channel: ChannelRef, val handle: String?, val subscribers: Long) : SearchHit {
        override val key get() = "c-" + channel.id
    }

    @Immutable
    data class Playlist(val playlist: PlaylistRef, val channel: String?) : SearchHit {
        override val key get() = "p-" + playlist.id
    }
}

@Immutable
data class SearchPage(val hits: List<SearchHit>, val next: String?)

/** What kind of results: YouTube's Type filter (2 in the filter message). Shorts and Movies are left out (not played here). */
enum class SearchType(val code: Int, @StringRes val labelRes: Int) {
    All(0, R.string.search_type_all),
    Videos(1, R.string.search_type_videos),
    Channels(2, R.string.search_type_channels),
    Playlists(3, R.string.search_type_playlists),
}

/** YouTube's Upload date filter (1 in the filter message). */
enum class SearchDate(val code: Int, @StringRes val labelRes: Int) {
    Any(0, R.string.search_date_any),
    Today(2, R.string.search_date_today),
    Week(3, R.string.search_date_week),
    Month(4, R.string.search_date_month),
    Year(5, R.string.search_date_year),
}

/** YouTube's Duration filter (3 in the filter message): under 3 min, 3–20 min, over 20 min. */
enum class SearchLength(val code: Int, @StringRes val labelRes: Int) {
    Any(0, R.string.search_length_any),
    Short(4, R.string.search_length_short),
    Medium(5, R.string.search_length_medium),
    Long(2, R.string.search_length_long),
}

/** YouTube's "Prioritize" (1 in the outer message): Relevance (the default) or Popularity. */
enum class SearchSort(val code: Int, @StringRes val labelRes: Int) {
    Relevance(0, R.string.search_sort_relevance),
    Popularity(3, R.string.search_sort_popularity),
}

@Immutable
data class SearchFilters(
    val type: SearchType = SearchType.All,
    val date: SearchDate = SearchDate.Any,
    val length: SearchLength = SearchLength.Any,
    val sort: SearchSort = SearchSort.Relevance,
) {
    /** Filters that only apply to videos (date, length, sort) are dropped for channels and playlists, as YouTube's page does. */
    val effective: SearchFilters
        get() = if (type == SearchType.Channels || type == SearchType.Playlists) copy(date = SearchDate.Any, length = SearchLength.Any, sort = SearchSort.Relevance) else this

    /** How many of the Filters sheet's choices are set and sent (the Filters chip shows it; Channels / Playlists send none). */
    val refinements: Int get() = effective.let { f -> listOf(f.date != SearchDate.Any, f.length != SearchLength.Any, f.sort != SearchSort.Relevance).count { it } }

    /**
     * The search's `params`: YouTube's SearchParams protobuf, base64. Outer field 1 = sort, field 2 = the filter
     * message {1 upload date, 2 type, 3 duration}. Checked against the params YouTube's own filter menu carries
     * (search.json: Videos "EgIQAQ==", Today "EgIIAg==", Over 20 minutes "EgIYAg==", Popularity "CAM=").
     * Null = no filter at all (YouTube's plain search).
     */
    fun params(): String? {
        val f = effective
        val inner = ArrayList<Byte>()
        if (f.date.code != 0) { inner += 0x08; inner += f.date.code.toByte() }
        if (f.type.code != 0) { inner += 0x10; inner += f.type.code.toByte() }
        if (f.length.code != 0) { inner += 0x18; inner += f.length.code.toByte() }
        val out = ArrayList<Byte>()
        if (f.sort.code != 0) { out += 0x08; out += f.sort.code.toByte() }
        if (inner.isNotEmpty()) { out += 0x12; out += inner.size.toByte(); out += inner }
        if (out.isEmpty()) return null
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }
}

object SearchSuggestions {
    /** YouTube's suggestions for what's typed so far (the extractor's suggestion list; English, as everything asked of YouTube). */
    suspend fun forQuery(query: String): List<String> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        ServiceList.YouTube.suggestionExtractor.suggestionList(q).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX)
    }

    private const val MAX = 8
}
