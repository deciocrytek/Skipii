package com.decio.skipii

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource

/**
 * DEBUG BUILDS ONLY. `adb shell am broadcast -n com.skipii/com.decio.skipii.DebugSeed` stores two ready downloads made from
 * Google's public "Tears of Steel" DASH sample (video 240p + sound, and sound only), exactly as Downloads keeps a real one:
 * bytes under stable keys, manifests whose BaseURL is the key. Lets offline playback be checked where YouTube refuses streams.
 */
@OptIn(UnstableApi::class)
class DebugSeed : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            runCatching { seed(context.applicationContext) }.onFailure { Log.w("Skipii", "debug seed failed", it) }
            pending.finish()
        }.start()
    }

    private fun manifest(key: String, mime: String, codecs: String, bandwidth: Int, extra: String, init: String, index: String, seconds: Int, video: Boolean) = """<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" minBufferTime="PT2S" mediaPresentationDuration="PT${seconds}S">
<Period id="0"><AdaptationSet id="0" contentType="${if (video) "video" else "audio"}"><Representation id="1" bandwidth="$bandwidth" codecs="$codecs" mimeType="$mime" $extra>
<BaseURL>$key</BaseURL><SegmentBase indexRange="$index"><Initialization range="$init"/></SegmentBase></Representation></AdaptationSet></Period></MPD>"""

    private fun seed(context: Context) {
        val cache = Downloads.testCache() ?: error("no cache")
        val base = "https://storage.googleapis.com/wvmedia/clear/h264/tears/"
        val upstream = OkHttpDataSource.Factory(Net.client)
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream).createDataSource()
        fun fetch(file: String, key: String, bytes: Long) {
            Log.i("Skipii", "debug seed: $file → $key")
            CacheWriter(source, DataSpec.Builder().setUri(Uri.parse(base + file)).setKey(key).setPosition(0).setLength(bytes).build(), null, null).cache()
        }
        val aKey = "skipii-dl://TESTSTEEL01/140"
        val vKey = "skipii-dl://TESTSTEEL01/133"
        val aBytes = 12073226L
        val vBytes = 70579096L
        fetch("tears_audio_eng.mp4", aKey, aBytes)
        fetch("tears_h264_baseline_240p_800.mp4", vKey, vBytes)
        // The sound-only one has its own copy (as real downloads: keys are per video), so deleting one never breaks the other.
        val auKey = "skipii-dl://TESTSTEELAU/140"
        fetch("tears_audio_eng.mp4", auKey, aBytes)
        // A kept caption file, linked the way YouTube's are (checks the offline caption lookup on Android's JSON).
        val captionUrl = "https://www.youtube.com/api/timedtext?v=TESTSTEEL01&caps=asr&lang=en&fmt=srv3"
        java.io.File(context.filesDir, "downloads/captions").apply { mkdirs() }.resolve("TESTSTEEL01_0.srv3").writeText(
            """<?xml version="1.0" encoding="utf-8" ?><timedtext format="3"><body><p t="0" d="734000">Kept caption from the download</p></body></timedtext>""",
        )
        val audio = Downloads.Track(aKey, 140, manifest(aKey, "audio/mp4", "mp4a.40.2", 131596, "audioSamplingRate=\"44100\"", "0-744", "745-1664", 734, false), aBytes)
        val video = Downloads.Track(vKey, 133, manifest(vKey, "video/mp4", "avc1.42c01e", 769255, "width=\"320\" height=\"142\"", "0-826", "827-1602", 734, true), vBytes)
        val snapshot = org.json.JSONObject().put("title", "Tears of Steel (test download)").put("channel", "Blender Foundation")
            .put("description", "A test download made from Google's public DASH sample.").put("aspect", 320.0 / 142.0)
            .put("chapters", org.json.JSONArray())
            .put("captions", org.json.JSONArray().put(org.json.JSONObject().put("lang", "en").put("auto", false).put("url", captionUrl).put("file", "TESTSTEEL01_0.srv3")))
            .put("segments", org.json.JSONArray().put(
                org.json.JSONObject().put("uuid", "t1").put("category", "sponsor").put("start", 20_000).put("end", 30_000)))
        fun item(id: String, title: String) = VideoItem(id, title, "Blender Foundation", null, "https://i.ytimg.com/vi/R6MlUcmOul8/hqdefault.jpg", null, 734, 1000, null)
        Downloads.putReadyForTest(Downloads.Entry(item("TESTSTEEL01", "Tears of Steel (test download)"), 240, Downloads.Source.Manual, System.currentTimeMillis(),
            Downloads.Status.Done, video, audio, vBytes + aBytes, vBytes + aBytes, null, snapshot.toString()))
        Downloads.putReadyForTest(Downloads.Entry(item("TESTSTEELAU", "Tears of Steel (sound only)"), null, Downloads.Source.Queue, System.currentTimeMillis() - 1000,
            Downloads.Status.Done, null, audio.copy(key = auKey, manifest = audio.manifest.replace(aKey, auKey)), aBytes, aBytes, null,
            org.json.JSONObject(snapshot.toString()).put("title", "Tears of Steel (sound only)").put("captions", org.json.JSONArray()).toString()))
        Log.i("Skipii", "debug seed: done")
    }
}
