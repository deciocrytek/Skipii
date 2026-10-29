package com.decio.skipii

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "Save to playlist" answer (playlist/get_add_to_playlist, parseSaveChoices). NOT a real
 * capture: it needs a signed-in account, so this is the web answer's shape written by hand.
 * Replace it with a capture once one exists.
 */
class SaveChoicesParseTest {
    private val answer = JSONObject(
        """
        {"contents":[{"addToPlaylistRenderer":{
          "playlists":[
            {"playlistAddToOptionRenderer":{"playlistId":"WL","title":{"simpleText":"Watch later"},"privacy":"PRIVATE","containsSelectedVideos":"NONE"}},
            {"playlistAddToOptionRenderer":{"playlistId":"PL1","title":{"runs":[{"text":"Road trip"}]},"privacy":"UNLISTED","containsSelectedVideos":"ALL"}},
            {"playlistAddToOptionRenderer":{"playlistId":"PL2","title":{"simpleText":"Cooking"},"privacy":"PUBLIC"}}
          ],
          "actions":[{"addToPlaylistCreateRenderer":{}}]
        }}]}
        """,
    )

    @Test
    fun choices() {
        assertEquals(
            listOf(
                PlaylistChoice("WL", "Watch later", contains = false, privacy = "PRIVATE"),
                PlaylistChoice("PL1", "Road trip", contains = true, privacy = "UNLISTED"),
                PlaylistChoice("PL2", "Cooking", contains = null, privacy = "PUBLIC"),
            ),
            parseSaveChoices(answer),
        )
    }

    @Test
    fun nothingToRead() {
        assertEquals(emptyList<PlaylistChoice>(), parseSaveChoices(JSONObject("{\"contents\":[]}")))
    }
}
