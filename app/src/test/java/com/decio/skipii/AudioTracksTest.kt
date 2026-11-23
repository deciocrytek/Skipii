package com.decio.skipii

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale

class AudioTracksTest {
    private val multiTracks = listOf(
        AudioTrack(id = "en-US.4", language = "en-US", rawName = "English (US)", type = AudioTrackType.ORIGINAL),
        AudioTrack(id = "es.3", language = "es", rawName = "Spanish", type = AudioTrackType.DUBBED),
        AudioTrack(id = "es-419.3", language = "es-419", rawName = "Spanish (Latin America)", type = AudioTrackType.DUBBED),
        AudioTrack(id = "fr.3", language = "fr", rawName = "French", type = AudioTrackType.DUBBED),
        AudioTrack(id = "de.3", language = "de", rawName = "German", type = AudioTrackType.DUBBED, autoDubbed = true),
        AudioTrack(id = "en-US.2", language = "en-US", rawName = "English (US)", type = AudioTrackType.DESCRIPTIVE),
    )

    @Test
    fun singleTrackEnglishVideoIsUnaffected() {
        val single = listOf(AudioTrack(id = "", language = "", rawName = "", type = null))
        assertEquals(
            "",
            AudioTrackPreference.resolveTrackId(
                tracks = single,
                mode = AudioTrackPreference.Mode.Custom,
                allowAutoDub = true,
                preferredLanguage = "es-419",
            ),
        )
    }

    @Test
    fun defaultOriginalModeAlwaysPlaysOriginal() {
        assertEquals(
            "en-US.4",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                mode = AudioTrackPreference.Mode.Original,
                preferredLanguage = "es-419",
            ),
        )
    }

    @Test
    fun perVideoOverrideWinsEvenInOriginalModeWithoutAffectingOtherVideos() {
        assertEquals(
            "fr.3",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                overrideId = "fr.3",
                mode = AudioTrackPreference.Mode.Original,
            ),
        )
        assertEquals(
            "en-US.4",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                overrideId = null,
                mode = AudioTrackPreference.Mode.Original,
            ),
        )
    }

    @Test
    fun detectsAutoDubAndDrcAndCleansRawNames() {
        assertTrue(AudioTracks.isAutoDubbed("https://rr1.googlevideo.com/videoplayback?xtags=acont%3Ddubbed-auto%3Alang%3Dde", "German"))
        assertTrue(AudioTracks.isAutoDubbed("https://rr1.googlevideo.com/videoplayback?xtags=acont=dubbed-auto:lang=de", null))
        assertTrue(AudioTracks.isAutoDubbed(null, "German (auto-dubbed)"))
        assertFalse(AudioTracks.isAutoDubbed("https://rr1.googlevideo.com/videoplayback?xtags=acont%3Ddubbed%3Alang%3Des", "Spanish"))
        assertTrue(AudioTracks.isDrc("https://rr1.googlevideo.com/videoplayback?xtags=drc%3D1"))
        assertFalse(AudioTracks.isDrc("https://rr1.googlevideo.com/videoplayback?xtags=acont%3Doriginal"))
        assertEquals("English (US)", AudioTracks.cleanRawName("English (US) original"))
        assertEquals("German", AudioTracks.cleanRawName("German (auto-dubbed)"))
    }

    @Test
    fun sortingPinsOriginalThenAppThenLastPickedAndDescriptiveLast() {
        val sorted = AudioTracks.sort(multiTracks.shuffled(), appLocale = Locale.forLanguageTag("fr"), lastPicked = "es-419")
        assertEquals("en-US.4", sorted.first().id)
        assertEquals("fr.3", sorted[1].id)
        assertTrue(sorted[2].baseLanguage == "es" && sorted[3].baseLanguage == "es")
        assertEquals("de.3", sorted[4].id)
        assertEquals("en-US.2", sorted.last().id)
    }

    // StreamSelection.pick on a StreamInfo shaped like the extractor's: every track offered at two
    // bitrates, the original also as YouTube's louder DRC copy (higher bitrate, must not win).
    private fun audio(itag: Int, trackId: String?, lang: String?, type: AudioTrackType?, bitrate: Int, xtags: String = ""): AudioStream =
        AudioStream.Builder()
            .setId("$itag")
            .setContent("https://rr1.googlevideo.com/videoplayback?itag=$itag&id=${trackId.orEmpty()}$xtags", true)
            .setMediaFormat(if (itag == 140 || itag == 139) MediaFormat.M4A else MediaFormat.WEBMA_OPUS)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setAverageBitrate(bitrate)
            .apply {
                if (trackId != null) setAudioTrackId(trackId)
                if (lang != null) setAudioLocale(Locale.forLanguageTag(lang))
                if (type != null) setAudioTrackType(type)
            }
            .build()

    private fun info(id: String, streams: List<AudioStream>) =
        StreamInfo(0, "https://www.youtube.com/watch?v=$id", "https://www.youtube.com/watch?v=$id", StreamType.VIDEO_STREAM, id, "Test", 0)
            .apply { audioStreams = streams }

    private val dubbedVideo = info(
        "dubbedVideo",
        listOf(
            audio(140, "en-US.4", "en-US", AudioTrackType.ORIGINAL, 129_000),
            audio(140, "en-US.4", "en-US", AudioTrackType.ORIGINAL, 160_000, "&xtags=drc%3D1"),
            audio(251, "en-US.4", "en-US", AudioTrackType.ORIGINAL, 140_000),
            audio(140, "es-419.3", "es-419", AudioTrackType.DUBBED, 129_000),
            audio(251, "es-419.3", "es-419", AudioTrackType.DUBBED, 140_000),
            audio(140, "de.3", "de", AudioTrackType.DUBBED, 129_000, "&xtags=acont%3Ddubbed-auto%3Alang%3Dde"),
        ),
    )

    @Test
    fun englishSingleTrackVideoPicksAsBeforeAndOffersNoPicker() {
        val single = info(
            "englishVideo",
            listOf(audio(139, null, null, null, 48_000), audio(140, null, null, null, 129_000), audio(251, null, null, null, 140_000)),
        )
        assertTrue(AudioTracks.from(single).isEmpty())
        val picked = StreamSelection.pick(single, "englishVideo").audio!!
        assertEquals(129_000, picked.averageBitrate)
        assertEquals(MediaFormat.M4A, picked.format)
    }

    @Test
    fun dubbedVideoStartsOnTheOriginalNonDrcStream() {
        val tracks = AudioTracks.from(dubbedVideo, Locale.US, lastPicked = null)
        assertEquals(listOf("en-US.4", "es-419.3", "de.3"), tracks.map { it.id })
        assertTrue(tracks.first { it.id == "de.3" }.autoDubbed)
        val picked = StreamSelection.pick(dubbedVideo, "dubbedVideo").audio!!
        assertEquals("en-US.4", picked.audioTrackId)
        assertEquals(129_000, picked.averageBitrate)
        assertFalse(AudioTracks.isDrc(picked.content))
    }

    @Test
    fun pickingADubSwitchesOnlyThatVideo() {
        val spanish = AudioTracks.from(dubbedVideo, Locale.US, lastPicked = null).first { it.id == "es-419.3" }
        AudioTrackPreference.setForVideo("dubbedVideo", spanish)
        try {
            val picked = StreamSelection.pick(dubbedVideo, "dubbedVideo").audio!!
            assertEquals("es-419.3", picked.audioTrackId)
            assertEquals(MediaFormat.M4A, picked.format)
            // The same tracks under another video id keep the original.
            assertEquals("en-US.4", StreamSelection.pick(dubbedVideo, "anotherVideo").audio!!.audioTrackId)
        } finally {
            AudioTrackPreference.clearOverrides()
        }
        assertEquals("en-US.4", StreamSelection.pick(dubbedVideo, "dubbedVideo").audio!!.audioTrackId)
    }

    @Test
    fun customLanguageModeDubsOnlyWhenSelectedAndNeverAffectsUnselectedOrSameOriginal() {
        // Custom mode with no language selected yet must leave videos on Original.
        assertEquals(
            "en-US.4",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                mode = AudioTrackPreference.Mode.Custom,
                preferredLanguage = null,
            ),
        )
        // Choosing English leaves an English-original video on Original untouched.
        assertEquals(
            "en-US.4",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                mode = AudioTrackPreference.Mode.Custom,
                preferredLanguage = "en",
            ),
        )
        // Choosing Spanish (Latin America) picks es-419.3; choosing Spanish picks es.3.
        assertEquals(
            "es-419.3",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                mode = AudioTrackPreference.Mode.Custom,
                preferredLanguage = "es-419",
            ),
        )
        assertEquals(
            "es.3",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                mode = AudioTrackPreference.Mode.Custom,
                preferredLanguage = "es",
            ),
        )
        // Choosing a language not offered falls back to Original.
        assertEquals(
            "en-US.4",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                mode = AudioTrackPreference.Mode.Custom,
                preferredLanguage = "ja",
            ),
        )
        // Auto-dubbed German is still skipped unless allowAutoDub is enabled.
        assertEquals(
            "en-US.4",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                mode = AudioTrackPreference.Mode.Custom,
                allowAutoDub = false,
                preferredLanguage = "de",
            ),
        )
        assertEquals(
            "de.3",
            AudioTrackPreference.resolveTrackId(
                tracks = multiTracks,
                mode = AudioTrackPreference.Mode.Custom,
                allowAutoDub = true,
                preferredLanguage = "de",
            ),
        )
        // Choosing English on a non-English video picks the English dub automatically.
        val japaneseOriginal = listOf(
            AudioTrack(id = "ja.4", language = "ja", rawName = "Japanese", type = AudioTrackType.ORIGINAL),
            AudioTrack(id = "en.3", language = "en", rawName = "English", type = AudioTrackType.DUBBED),
        )
        assertEquals(
            "en.3",
            AudioTrackPreference.resolveTrackId(
                tracks = japaneseOriginal,
                mode = AudioTrackPreference.Mode.Custom,
                preferredLanguage = "en",
            ),
        )
    }

    @Test
    fun customLanguageModeWorksEndToEndInStreamSelectionAndSorting() {
        val sorted = AudioTracks.sort(multiTracks.shuffled(), appLocale = Locale.US, lastPicked = null, preferredLanguage = "fr")
        assertEquals("en-US.4", sorted[0].id)
        assertEquals("fr.3", sorted[1].id)
        try {
            AudioTrackPreference.choosePreferredLanguage("es-419")
            assertEquals("es-419.3", StreamSelection.pick(dubbedVideo, "dubbedVideo").audio!!.audioTrackId)
            AudioTrackPreference.chooseMode(AudioTrackPreference.Mode.Original)
            assertEquals("en-US.4", StreamSelection.pick(dubbedVideo, "dubbedVideo").audio!!.audioTrackId)
        } finally {
            AudioTrackPreference.chooseMode(AudioTrackPreference.Mode.Original)
            AudioTrackPreference.clearOverrides()
        }
    }

    @Test
    fun titleDisambiguatesSharedLanguageRegions() {
        val es = multiTracks.first { it.id == "es.3" }
        val esLatam = multiTracks.first { it.id == "es-419.3" }
        val fr = multiTracks.first { it.id == "fr.3" }
        assertEquals("French", fr.title(multiTracks, Locale.US))
        assertTrue(esLatam.title(multiTracks, Locale.US).contains("Latin America"))
        assertEquals("Spanish", es.title(multiTracks, Locale.US))
    }
}
