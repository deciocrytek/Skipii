package com.decio.skipii

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player

/** The app's single shared video surface (see SkipiiApp). */
val LocalVideoSurface = androidx.compose.runtime.staticCompositionLocalOf<@Composable (Modifier) -> Unit> { { m -> VideoSurface(null, m) } }

/** Draws nothing: where the video must not show (picture-in-picture shows it elsewhere). */
val NoVideoSurface: @Composable (Modifier) -> Unit = {}

/**
 * The one surface the decoder draws into, kept alive for the whole process.
 *
 * Why: a TextureView destroys its SurfaceTexture whenever it leaves the window (moving it
 * from the full player to the mini-player does exactly that). The decoder then loses its
 * output, is rebuilt, and waits for the next keyframe — seconds of frozen picture, the
 * "flash" when minimizing. Here the SurfaceTexture is never released (the destroy callback
 * returns false) and is handed back to whichever TextureView shows the video next, so the
 * player's surface never changes and playback never notices a move.
 */
object SharedVideoTexture {
    var texture: SurfaceTexture? = null
        private set
    var surface: Surface? = null
        private set
    /** Bumped when the surface first exists, so players can be pointed at it. */
    var version by mutableIntStateOf(0)
        private set

    fun adopt(view: TextureView, fresh: SurfaceTexture) {
        val kept = texture
        if (kept == null) {
            texture = fresh
            surface = Surface(fresh)
            version++
        } else if (kept !== fresh) {
            // The view made a new one on re-attach: swap ours back in (releases the new one).
            runCatching { view.setSurfaceTexture(kept) }
                .onFailure { AppLog.w("Skipii", "couldn't re-attach the video texture", it) }
        }
    }
}

/**
 * The video, in a TextureView this app owns. All TextureViews show [SharedVideoTexture];
 * the player's output surface is set once and never cleared while the app runs.
 */
@Composable
fun VideoSurface(player: Player?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                // A view created after the texture exists shows it straight away.
                SharedVideoTexture.texture?.let { runCatching { setSurfaceTexture(it) } }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = SharedVideoTexture.adopt(this@apply, st)
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                    // Never let the view release it: the next view (or this one, re-attached) reuses it.
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = st !== SharedVideoTexture.texture
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = AvSync.onFrameDrawn()
                }
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        // Before the hardware layer is built, so it adopts the kept texture.
                        val kept = SharedVideoTexture.texture ?: return
                        if (surfaceTexture !== kept) runCatching { setSurfaceTexture(kept) }
                    }
                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
        },
    )
    val version = SharedVideoTexture.version
    LaunchedEffect(player, version) {
        val surface = SharedVideoTexture.surface ?: return@LaunchedEffect
        player?.setVideoSurface(surface)
    }
}
