// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package dev.flutter.packages.interactive_media_ads

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.View.MeasureSpec
import android.widget.FrameLayout

/**
 * Custom TextureView-backed video player view for IMA SDK integration in Flutter.
 * Replaces android.widget.VideoView (which uses SurfaceView) to eliminate:
 * 1. Z-order / hardware surface bleed outside Flutter card boundaries (status bar, search header, tab strip).
 * 2. Composition tearing and scroll jank during card swiping / scrolling.
 */
class TextureVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), TextureView.SurfaceTextureListener {

    private val textureView: TextureView = TextureView(context)
    private var mediaPlayer: MediaPlayer? = null
    private var surface: Surface? = null

    private var videoUri: Uri? = null
    private var isPrepared = false
    private var isSurfaceReady = false
    private var currentState = STATE_IDLE

    // The plugin adds this view to its FrameLayout container with NO layout
    // params, exactly as it did for android.widget.VideoView before it — so
    // that container was relying on VideoView's own onMeasure to shrink to the
    // creative's aspect ratio and land pinned top-left (Android's default
    // FrameLayout child gravity) whenever the creative's aspect doesn't match
    // the box it's handed. A plain TextureView has no such behavior — it just
    // stretches to fill MATCH_PARENT — which would visibly distort any
    // mismatched-aspect creative (see ad_video_item.dart's own sizing-math
    // comments, which depend on exactly this VideoView letterbox behavior).
    // onMeasure below ports VideoView's own algorithm so this view keeps
    // producing the same box VideoView always did; the inner TextureView is
    // MATCH_PARENT relative to THIS view, so it automatically fills whatever
    // (now aspect-correct) box this measures itself to.
    private var videoWidth = 0
    private var videoHeight = 0

    private var onPreparedListener: MediaPlayer.OnPreparedListener? = null
    private var onErrorListener: MediaPlayer.OnErrorListener? = null
    private var onCompletionListener: MediaPlayer.OnCompletionListener? = null

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    companion object {
        private const val STATE_ERROR = -1
        private const val STATE_IDLE = 0
        private const val STATE_PREPARING = 1
        private const val STATE_PREPARED = 2
        private const val STATE_PLAYING = 3
        private const val STATE_PAUSED = 4
        private const val STATE_PLAYBACK_COMPLETED = 5
    }

    init {
        textureView.surfaceTextureListener = this
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /// Ports android.widget.VideoView#onMeasure verbatim (same branch shape,
    /// same integer math) so this view's measured size — and therefore where
    /// the (unaware) FrameLayout parent lands it — is identical to what
    /// VideoView already produced for the exact same box/creative pair.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var width = getDefaultSize(videoWidth, widthMeasureSpec)
        var height = getDefaultSize(videoHeight, heightMeasureSpec)
        if (videoWidth > 0 && videoHeight > 0) {
            val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSpecSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSpecSize = MeasureSpec.getSize(heightMeasureSpec)

            if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
                // The parent (the plugin's un-sized FrameLayout, itself sized
                // by Flutter's platform-view embedding) gave an exact box —
                // fill it, but correct one dimension against the other so the
                // creative's own aspect ratio still governs the shape.
                width = widthSpecSize
                height = heightSpecSize
                if (videoWidth * height < width * videoHeight) {
                    width = height * videoWidth / videoHeight
                } else if (videoWidth * height > width * videoHeight) {
                    height = width * videoHeight / videoWidth
                }
            } else if (widthSpecMode == MeasureSpec.EXACTLY) {
                width = widthSpecSize
                height = width * videoHeight / videoWidth
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    height = heightSpecSize
                }
            } else if (heightSpecMode == MeasureSpec.EXACTLY) {
                height = heightSpecSize
                width = height * videoWidth / videoHeight
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    width = widthSpecSize
                }
            } else {
                width = videoWidth
                height = videoHeight
                if (heightSpecMode == MeasureSpec.AT_MOST && height > heightSpecSize) {
                    height = heightSpecSize
                    width = height * videoWidth / videoHeight
                }
                if (widthSpecMode == MeasureSpec.AT_MOST && width > widthSpecSize) {
                    width = widthSpecSize
                    height = width * videoHeight / videoWidth
                }
            }
        }
        setMeasuredDimension(width, height)
    }

    fun setOnPreparedListener(l: MediaPlayer.OnPreparedListener?) {
        onPreparedListener = l
    }

    fun setOnErrorListener(l: MediaPlayer.OnErrorListener?) {
        onErrorListener = l
    }

    fun setOnCompletionListener(l: MediaPlayer.OnCompletionListener?) {
        onCompletionListener = l
    }

    fun setVideoURI(uri: Uri?) {
        videoUri = uri
        openVideo()
    }

    fun setVideoUri(uriString: String?) {
        videoUri = uriString?.let { Uri.parse(it) }
        openVideo()
    }

    private fun openVideo() {
        if (videoUri == null || !isSurfaceReady) return
        releaseMediaPlayer(keepSurface = true)
        try {
            val mp = MediaPlayer()
            mediaPlayer = mp
            surface?.let { mp.setSurface(it) }
            mp.setDataSource(context, videoUri!!)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
            } else {
                @Suppress("DEPRECATION")
                mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            }

            mp.setOnVideoSizeChangedListener { _, width, height ->
                // Fires once the creative's real dimensions are known (may
                // race `onPrepared` in either order per MediaPlayer's docs) —
                // triggers a re-measure so onMeasure's aspect correction has
                // real numbers instead of the 0x0 default.
                if (width > 0 && height > 0) {
                    videoWidth = width
                    videoHeight = height
                    requestLayout()
                }
            }
            mp.setOnPreparedListener { player ->
                currentState = STATE_PREPARED
                isPrepared = true
                onPreparedListener?.onPrepared(player)
            }
            mp.setOnCompletionListener { player ->
                currentState = STATE_PLAYBACK_COMPLETED
                onCompletionListener?.onCompletion(player)
            }
            mp.setOnErrorListener { player, what, extra ->
                currentState = STATE_ERROR
                onErrorListener?.onError(player, what, extra) ?: true
            }

            currentState = STATE_PREPARING
            mp.prepareAsync()
        } catch (e: Exception) {
            currentState = STATE_ERROR
            onErrorListener?.onError(mediaPlayer, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(surfaceTexture)
        isSurfaceReady = true
        if (videoUri != null && mediaPlayer == null) {
            openVideo()
        } else if (mediaPlayer != null) {
            mediaPlayer?.setSurface(surface)
        }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        surface?.release()
        surface = null
        isSurfaceReady = false
        mediaPlayer?.setSurface(null)
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}

    fun getCurrentPosition(): Long {
        return try {
            if (mediaPlayer != null && (currentState == STATE_PREPARED || currentState == STATE_PLAYING || currentState == STATE_PAUSED)) {
                mediaPlayer!!.currentPosition.toLong()
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    fun setAudioFocusRequest(focusGain: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusGain == AudioManager.AUDIOFOCUS_NONE) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                val req = AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            }
        }
    }

    fun releaseMediaPlayer(keepSurface: Boolean = false) {
        try {
            mediaPlayer?.let { mp ->
                mp.reset()
                mp.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        isPrepared = false
        currentState = STATE_IDLE
        // A new URI gets its own real size from onVideoSizeChanged — don't
        // let a stale aspect ratio from the PREVIOUS creative govern even one
        // measure pass of the next one.
        videoWidth = 0
        videoHeight = 0
        if (!keepSurface) {
            surface?.release()
            surface = null
            isSurfaceReady = false
        }
    }
}
