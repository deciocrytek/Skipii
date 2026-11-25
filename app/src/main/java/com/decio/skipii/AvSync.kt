package com.decio.skipii

import android.content.Context
import android.os.Build
import android.os.Handler
import android.view.Choreographer
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import kotlin.math.abs

/**
 * Lip sync for the TextureView.
 *
 * ExoPlayer times frames for a SurfaceView: it hands each one over up to 50 ms early, stamped
 * with the moment it should appear, and SurfaceFlinger holds it until then. A TextureView
 * ignores the stamp — the frame shows with the app's next drawn frame — so the picture is off
 * from the audio by the gap between the two. The audio side is already right (ExoPlayer
 * follows the output's reported latency, Bluetooth included), so only the video is corrected.
 *
 * Every drawn video frame is measured: when the app frame showing it is expected on screen
 * (Choreographer) against when ExoPlayer meant it to appear. The median gap shifts the clock
 * the video renderer sees ([SyncedVideoRenderer]), so frames are released that much later or
 * earlier. The gap is measured against the release stamp, which moves with the shift, so the
 * measurement doesn't chase its own correction. It follows the refresh rate by itself.
 * Below Android 13 there's no expected-present time to measure against; no correction.
 */
object AvSync {
    /** Added to the position the video renderer sees. Read on the playback thread. */
    @Volatile
    var offsetUs = 0L
        private set

    // The newest scheduled frame's release stamp (ns), written on the playback thread.
    @Volatile
    private var releaseNs = 0L

    /** From ExoPlayer's frame metadata listener, just before a frame goes to the surface. */
    fun onFrameReleased(releaseTimeNs: Long) {
        // Frames released immediately (paused, seeking, very late) carry "now" as their
        // stamp, not a target; they would skew the median.
        if (releaseTimeNs - System.nanoTime() > MIN_SCHEDULED_NS) releaseNs = releaseTimeNs
    }

    // UI thread only from here.

    private val samples = LongArray(WINDOW)
    private var sampleCount = 0
    private var nextSample = 0
    private var lastSampledNs = 0L

    private var watching = false
    private var lastDrawnNs = 0L
    private var frameVsyncNs = 0L
    private var framePresentNs = 0L
    private var framePeriodNs = 0L
    private var usedVsyncNs = 0L

    @RequiresApi(33)
    private val vsync = object : Choreographer.VsyncCallback {
        override fun onVsync(data: Choreographer.FrameData) {
            val timeline = data.preferredFrameTimeline
            frameVsyncNs = data.frameTimeNanos
            framePresentNs = timeline.expectedPresentationTimeNanos
            framePeriodNs = data.frameTimelines.let {
                if (it.size >= 2) it[1].expectedPresentationTimeNanos - it[0].expectedPresentationTimeNanos else 0L
            }.takeIf { it > 0 } ?: DEFAULT_PERIOD_NS
            // Runs before this frame's draw; keeps going only while video frames keep coming.
            if (System.nanoTime() - lastDrawnNs < IDLE_NS) Choreographer.getInstance().postVsyncCallback(this)
            else watching = false
        }
    }

    /** The video TextureView drew a new frame (onSurfaceTextureUpdated, during the draw). */
    fun onFrameDrawn() {
        if (Build.VERSION.SDK_INT < 33) return
        lastDrawnNs = System.nanoTime()
        if (!watching) {
            // No frame data for this frame yet; measuring starts with the next one.
            watching = true
            Choreographer.getInstance().postVsyncCallback(vsync)
            return
        }
        if (frameVsyncNs == usedVsyncNs) return // a second view drawing in the same frame
        usedVsyncNs = frameVsyncNs
        val released = releaseNs
        if (released == 0L || released == lastSampledNs) return
        lastSampledNs = released
        // ExoPlayer releases 80% of a vsync ahead of the vsync it means the frame to show at.
        val intendedNs = released + framePeriodNs * 8 / 10
        val gapUs = (framePresentNs - intendedNs) / 1000
        if (gapUs !in -MAX_GAP_US..MAX_GAP_US) return // stale stamp (seek, pause, stall)
        samples[nextSample] = gapUs
        nextSample = (nextSample + 1) % WINDOW
        if (sampleCount < WINDOW) sampleCount++
        if (sampleCount < MIN_SAMPLES) return
        val median = samples.copyOf(sampleCount).apply { sort() }[sampleCount / 2]
        val next = median.coerceIn(-MAX_OFFSET_US, MAX_OFFSET_US)
        if (abs(next - offsetUs) >= HYSTERESIS_US) {
            AppLog.i(
                "Skipii",
                "AV sync: frames show ${abs(median) / 1000} ms ${if (median < 0) "early" else "late"} " +
                    "(${1_000_000_000L / framePeriodNs} Hz); video clock shifted ${next / 1000} ms",
            )
            offsetUs = next
        }
    }

    private const val WINDOW = 61
    private const val MIN_SAMPLES = 15
    private const val MIN_SCHEDULED_NS = 1_000_000L
    private const val MAX_GAP_US = 150_000L
    private const val MAX_OFFSET_US = 100_000L
    private const val HYSTERESIS_US = 2_000L
    private const val IDLE_NS = 1_000_000_000L
    private const val DEFAULT_PERIOD_NS = 16_666_667L
}

/** The default renderers, with the video one replaced by [SyncedVideoRenderer]. */
@OptIn(UnstableApi::class)
class SyncedRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        // Same settings DefaultRenderersFactory uses; the app ships no extension decoders.
        out += SyncedVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(50),
        )
    }
}

/** Sees the playback position shifted by [AvSync.offsetUs], so frames reach the screen on time. */
@OptIn(UnstableApi::class)
private class SyncedVideoRenderer(builder: Builder) : MediaCodecVideoRenderer(builder) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) =
        super.render(positionUs + AvSync.offsetUs, elapsedRealtimeUs)
}
