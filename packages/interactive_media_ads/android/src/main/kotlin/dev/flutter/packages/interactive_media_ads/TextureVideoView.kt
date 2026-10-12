// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package dev.flutter.packages.interactive_media_ads

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.io.IOException

/**
 * MediaPlayer-shaped handle over the ExoPlayer that [TextureVideoView] created for the current
 * video. The Dart side drives playback through it exactly as it drove `android.media.MediaPlayer`.
 * The Dart side may keep a handle past the player's release; [detach] makes it inert.
 */
class AdMediaPlayer internal constructor(
    private var player: ExoPlayer?,
    private val onStart: () -> Unit = {},
) {
  /** Duration in ms, 0 while unknown (MediaPlayer reports 0 before prepare too). */
  val duration: Long
    get() {
      val d = player?.duration ?: C.TIME_UNSET
      return if (d == C.TIME_UNSET) 0L else d
    }

  val currentPosition: Long
    get() = player?.currentPosition ?: 0L

  fun seekTo(positionMs: Long) {
    player?.seekTo(positionMs)
  }

  fun start() {
    val p = player ?: return
    p.playWhenReady = true
    onStart()
  }

  fun pause() {
    player?.playWhenReady = false
  }

  fun stop() {
    player?.stop()
  }

  internal fun detach() {
    player = null
  }
}

/**
 * Reads 0 until this player's first media transfer has ended, then the shared meter's estimate.
 *
 * `AdaptiveTrackSelection` takes its first pick from the meter alone, so this is the only way to
 * start every unit on the lowest allowed rung without freezing later picks: an estimate of 0 fits
 * no rung and the selection falls back to the lowest the track selector permits, and once the
 * first segment has landed the shared history decides the second. Product call, device 2026-10-09 run 14: a burst-inflated
 * first pick is worse than five seconds of 144p.
 */
@OptIn(UnstableApi::class)
internal class ColdStartBandwidthMeter(private val shared: BandwidthMeter) :
    BandwidthMeter, TransferListener {
  @Volatile private var warm = false
  private val sharedListener: TransferListener? = shared.transferListener

  override fun getBitrateEstimate(): Long = if (warm) shared.bitrateEstimate else 0L

  override fun getTimeToFirstByteEstimateUs(): Long = shared.timeToFirstByteEstimateUs

  override fun getTransferListener(): TransferListener = this

  override fun addEventListener(
      eventHandler: android.os.Handler,
      eventListener: BandwidthMeter.EventListener
  ) = shared.addEventListener(eventHandler, eventListener)

  override fun removeEventListener(eventListener: BandwidthMeter.EventListener) =
      shared.removeEventListener(eventListener)

  override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
    sharedListener?.onTransferInitializing(source, dataSpec, isNetwork)
  }

  override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
    sharedListener?.onTransferStart(source, dataSpec, isNetwork)
  }

  override fun onBytesTransferred(
      source: DataSource,
      dataSpec: DataSpec,
      isNetwork: Boolean,
      bytesTransferred: Int
  ) {
    sharedListener?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
  }

  override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
    sharedListener?.onTransferEnd(source, dataSpec, isNetwork)
    warm = true
  }
}

/**
 * TextureView-backed ad renderer for the IMA SDK integration in Flutter.
 *
 * A TextureView (not SurfaceView) keeps the video inside Flutter card boundaries and out of the
 * z-order of the status bar and tab strip while scrolling. The video is decoded by a media3
 * ExoPlayer, which gives adaptive bitrate for HLS creatives, a first-frame callback and a buffering
 * callback, none of which `android.media.MediaPlayer` reports.
 *
 * One player per [setVideoUri]. The player is never released on view detach: the Flutter platform
 * view can be re-created around this same native view while an ad is prepared, and ExoPlayer
 * re-attaches to the texture by itself. [release] is the only teardown.
 */
@OptIn(UnstableApi::class)
class TextureVideoView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
    TextureView(context, attrs, defStyleAttr) {
  fun interface OnPreparedListener {
    fun onPrepared(player: AdMediaPlayer)
  }

  fun interface OnCompletionListener {
    fun onCompletion(player: AdMediaPlayer)
  }

  fun interface OnErrorListener {
    fun onError(player: AdMediaPlayer, what: Int, extra: Int)
  }

  fun interface OnRenderedFirstFrameListener {
    fun onRenderedFirstFrame()
  }

  fun interface OnBufferingListener {
    fun onBuffering(buffering: Boolean)
  }

  private var exoPlayer: ExoPlayer? = null
  private var adPlayer: AdMediaPlayer? = null
  private var videoUri: Uri? = null

  private var prepared = false
  private var buffering = false
  private var frameRendered = false
  private var renderStartReported = false

  private var videoWidth = 0
  private var videoHeight = 0

  private var onPreparedListener: OnPreparedListener? = null
  private var onCompletionListener: OnCompletionListener? = null
  private var onErrorListener: OnErrorListener? = null
  private var onRenderedFirstFrameListener: OnRenderedFirstFrameListener? = null
  private var onBufferingListener: OnBufferingListener? = null

  private val audioManager: AudioManager =
      context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private var audioFocusRequest: AudioFocusRequest? = null

  companion object {
    /** Mirrors `android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN`, what the Dart side expects. */
    const val MEDIA_ERROR_UNKNOWN = 1

    // Ad creatives are 10-30 s progressive files or short HLS ladders. The content player's
    // 50 s defaults would hold more than the whole ad in memory for nothing.
    private const val MIN_BUFFER_MS = 2_500
    private const val MAX_BUFFER_MS = 10_000
    private const val BUFFER_FOR_PLAYBACK_MS = 500
    private const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_500

    // ABR for a 10-30 s creative with 5 s segments: step up after one segment of evidence
    // instead of ExoPlayer's 10 s. Retain-after-discard stays at the 25 s default so a buffered
    // segment is never thrown away to refetch it higher (device 2026-10-09 run 12: that refetch
    // crawled on a shaped link). No in-flight segment cancel either: device 2026-10-09 run 13,
    // cancelling a segment whose samples were already playing made media3 HLS replay the segment
    // from its start (audio-sink discontinuity, picture jumping back) and re-request the same
    // rung, and one break was lost to IMA's 12 s media timeout while that repeated.
    private const val MIN_DURATION_FOR_QUALITY_INCREASE_MS = 2_000
    private const val MAX_DURATION_FOR_QUALITY_DECREASE_MS = 5_000
    private const val BANDWIDTH_FRACTION = 0.7f

    // A phone midroll gains nothing above 720p, and the ladders GAM serves carry 720p rungs at
    // 4.6-6.1 Mbps that are heavier than their 1080p ones, so the cap is both size and bitrate.
    // Bounds the damage of a burst-inflated pick to a ~1.6 MB segment (run 14: a 4.9 MB 1080p
    // segment on a 2.5 Mbps link cost 7.7 s of stall).
    private const val MAX_VIDEO_WIDTH = 1_280
    private const val MAX_VIDEO_HEIGHT = 720
    private const val MAX_VIDEO_BITRATE = 3_000_000

    // Floor: 144p is never selected, so the cold-start pick lands on 240p (Vivek, run 14). On a
    // link that cannot carry 240p the ad stalls there rather than dropping further.
    private const val MIN_VIDEO_HEIGHT = 240

    // What the HLS ladder's first segment is chosen against, before a byte has been measured. The
    // content player's meter is not consulted (device 2026-10-09: it read 8-90 Mbps on 300-2000 kbps
    // links). Cellular and unknown links start on the 360p rung so the first segment arrives fast;
    // Wi-Fi/ethernet start on the 480p-720p rung (device 2026-10-09: 800 kbps on an 80 Mbps Wi-Fi
    // kept the whole creative at 144p).
    private const val INITIAL_BITRATE_ESTIMATE_CELLULAR = 800_000L
    private const val INITIAL_BITRATE_ESTIMATE_WIFI = 1_500_000L

    // A shared meter that has not measured anything for this long is more likely to describe the
    // network the user left than the one they are on (device 2026-10-09: a break minutes after a
    // fast one opened on 1080p into a throttled link). Units of one pod are seconds apart and
    // keep sharing; the next break starts from the per-network estimate again.
    private const val METER_IDLE_RESET_MS = 60_000L
    @Volatile internal var lastMeterUseMs = 0L

    /** `adb shell setprop log.tag.AdExo DEBUG` to see segment loads and rendition switches. */
    private const val LOG_TAG = "AdExo"

    // Safety net for a player whose Dart-side release() never arrives (the container was
    // collected, or the SDK skipped stopAd). An ExoPlayer has no finalizer: its playback thread
    // keeps it alive, so a lost release would hold a decoder for the rest of the process. The
    // Flutter platform view is re-created around this same view when a preloaded slot becomes
    // visible, which detaches it for a frame or two; the delay is far longer than that gap.
    private const val ORPHAN_RELEASE_DELAY_MS = 15_000L

    // One meter for every ad player in the process. A fresh meter per unit starts each one from
    // the cold estimate, and the manifest fetches that precede the first segment (a few KB,
    // latency-bound) drag it further down: device 2026-10-09, every unit of a pod on an 80 Mbps
    // link opened on the 480p rung for its first 5 s segment and only then stepped to 1080p.
    // Shared, the second unit onwards starts from what the first one measured. The meter resets
    // itself when the network type changes.
    // Built on the application context, so the static reference leaks nothing.
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var sharedBandwidthMeter: DefaultBandwidthMeter? = null

    // Not private: the instance calls it, and lint rejects synthetic accessors.
    internal fun bandwidthMeter(context: Context): DefaultBandwidthMeter =
        synchronized(this) {
          val now = SystemClock.elapsedRealtime()
          val stale = now - lastMeterUseMs > METER_IDLE_RESET_MS
          lastMeterUseMs = now
          sharedBandwidthMeter?.takeUnless { stale }
              ?: DefaultBandwidthMeter.Builder(context.applicationContext)
                  .setInitialBitrateEstimate(INITIAL_BITRATE_ESTIMATE_CELLULAR)
                  .setInitialBitrateEstimate(C.NETWORK_TYPE_WIFI, INITIAL_BITRATE_ESTIMATE_WIFI)
                  .setInitialBitrateEstimate(
                      C.NETWORK_TYPE_ETHERNET, INITIAL_BITRATE_ESTIMATE_WIFI)
                  .build()
                  .also { sharedBandwidthMeter = it }
        }
  }

  private val orphanRelease = Runnable { release() }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    removeCallbacks(orphanRelease)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    if (exoPlayer != null) {
      postDelayed(orphanRelease, ORPHAN_RELEASE_DELAY_MS)
    }
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    var width = getDefaultSize(videoWidth, widthMeasureSpec)
    var height = getDefaultSize(videoHeight, heightMeasureSpec)
    if (videoWidth > 0 && videoHeight > 0) {
      val widthSpecMode = MeasureSpec.getMode(widthMeasureSpec)
      val widthSpecSize = MeasureSpec.getSize(widthMeasureSpec)
      val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
      val heightSpecSize = MeasureSpec.getSize(heightMeasureSpec)

      if (widthSpecMode == MeasureSpec.EXACTLY && heightSpecMode == MeasureSpec.EXACTLY) {
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

  fun setOnPreparedListener(l: OnPreparedListener?) {
    onPreparedListener = l
  }

  fun setOnCompletionListener(l: OnCompletionListener?) {
    onCompletionListener = l
  }

  fun setOnErrorListener(l: OnErrorListener?) {
    onErrorListener = l
  }

  fun setOnRenderedFirstFrameListener(l: OnRenderedFirstFrameListener?) {
    onRenderedFirstFrameListener = l
  }

  fun setOnBufferingListener(l: OnBufferingListener?) {
    onBufferingListener = l
  }

  fun setVideoUri(uriString: String?) {
    videoUri = uriString?.toUri()
    openVideo()
  }

  private fun openVideo() {
    val uri = videoUri ?: return
    release()
    val handle: AdMediaPlayer
    try {
      val trackSelector =
          DefaultTrackSelector(
              context,
              AdaptiveTrackSelection.Factory(
                  MIN_DURATION_FOR_QUALITY_INCREASE_MS,
                  MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                  AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                  BANDWIDTH_FRACTION))
      trackSelector.setParameters(
          trackSelector
              .buildUponParameters()
              .setMaxVideoSize(MAX_VIDEO_WIDTH, MAX_VIDEO_HEIGHT)
              .setMaxVideoBitrate(MAX_VIDEO_BITRATE)
              .setMinVideoSize(/* minVideoWidth= */ 0, MIN_VIDEO_HEIGHT))
      val player =
          ExoPlayer.Builder(context)
              .setBandwidthMeter(ColdStartBandwidthMeter(bandwidthMeter(context)))
              .setTrackSelector(trackSelector)
              .setLoadControl(
                  DefaultLoadControl.Builder()
                      .setBufferDurationsMs(
                          MIN_BUFFER_MS,
                          MAX_BUFFER_MS,
                          BUFFER_FOR_PLAYBACK_MS,
                          BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
                      .build())
              .build()
      handle = AdMediaPlayer(player, ::maybeReportRenderStarted)
      exoPlayer = player
      adPlayer = handle
      // Focus is requested explicitly through setAudioFocusRequest, as before.
      player.setAudioAttributes(
          AudioAttributes.Builder()
              .setUsage(C.USAGE_MEDIA)
              .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
              .build(),
          /* handleAudioFocus= */ false)
      player.setVideoTextureView(this)
      player.addListener(playerListener)
      player.addAnalyticsListener(loadLogger)
      player.setMediaItem(MediaItem.fromUri(uri))
      player.playWhenReady = false
      player.prepare()
    } catch (e: Exception) {
      onErrorListener?.onError(adPlayer ?: AdMediaPlayer(null), MEDIA_ERROR_UNKNOWN, 0)
    }
  }

  // Device-QA visibility into what the ladder actually did: the ladder itself, every segment
  // load, every rendition switch, every load error and every meter sample. Silent unless the
  // AdExo tag is enabled with setprop.
  private val loadLogger =
      object : AnalyticsListener {
        override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
          if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            val ladder = StringBuilder("ladder")
            for (group in tracks.groups) {
              if (group.type != C.TRACK_TYPE_VIDEO) continue
              for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                ladder.append(' ').append(f.width).append('x').append(f.height).append('@')
                ladder.append(f.bitrate)
                if (group.isTrackSelected(i)) ladder.append('*')
              }
            }
            Log.d(LOG_TAG, ladder.toString())
          }
        }

        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
          if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            val format = mediaLoadData.trackFormat
            Log.d(
                LOG_TAG,
                "load type=${mediaLoadData.dataType} ${format?.width}x${format?.height} " +
                    "${format?.bitrate}bps bytes=${loadEventInfo.bytesLoaded} " +
                    "took=${loadEventInfo.loadDurationMs}ms media=" +
                    "${mediaLoadData.mediaStartTimeMs}-${mediaLoadData.mediaEndTimeMs}ms")
          }
        }

        override fun onLoadCanceled(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData
        ) {
          if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            val format = mediaLoadData.trackFormat
            Log.d(
                LOG_TAG,
                "cancelled ${format?.width}x${format?.height} ${format?.bitrate}bps " +
                    "bytes=${loadEventInfo.bytesLoaded} after=${loadEventInfo.loadDurationMs}ms")
          }
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
          if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            val format = mediaLoadData.trackFormat
            Log.d(
                LOG_TAG,
                "loadError type=${mediaLoadData.dataType} ${format?.width}x${format?.height} " +
                    "${format?.bitrate}bps bytes=${loadEventInfo.bytesLoaded} " +
                    "after=${loadEventInfo.loadDurationMs}ms canceled=$wasCanceled " +
                    "${error.javaClass.simpleName}: ${error.message}")
          }
        }

        override fun onDownstreamFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            mediaLoadData: MediaLoadData
        ) {
          if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            val format = mediaLoadData.trackFormat
            Log.d(
                LOG_TAG,
                "rendition ${format?.width}x${format?.height} ${format?.bitrate}bps " +
                    "at=${mediaLoadData.mediaStartTimeMs}ms")
          }
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long
        ) {
          if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
            Log.d(
                LOG_TAG,
                "meter ${bitrateEstimate}bps sample=${totalBytesLoaded}B/${totalLoadTimeMs}ms")
          }
        }
      }

  private val playerListener =
      object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
          val handle = adPlayer ?: return
          when (playbackState) {
            Player.STATE_READY -> {
              if (buffering) {
                buffering = false
                onBufferingListener?.onBuffering(false)
              }
              if (!prepared) {
                prepared = true
                onPreparedListener?.onPrepared(handle)
              }
            }
            Player.STATE_BUFFERING -> {
              // Before READY this is the initial load, which the IMA SDK's own
              // media-load timeout already covers.
              if (prepared && !buffering) {
                buffering = true
                onBufferingListener?.onBuffering(true)
              }
            }
            Player.STATE_ENDED -> onCompletionListener?.onCompletion(handle)
            Player.STATE_IDLE -> {}
          }
        }

        override fun onPlayerError(error: PlaybackException) {
          val handle = adPlayer ?: return
          onErrorListener?.onError(handle, error.errorCode, 0)
        }

        override fun onRenderedFirstFrame() {
          frameRendered = true
          maybeReportRenderStarted()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
          if (videoSize.width > 0 && videoSize.height > 0) {
            videoWidth = videoSize.width
            videoHeight = videoSize.height
            requestLayout()
          }
        }
      }

  // ExoPlayer paints the first frame as soon as it is READY, even while paused. "Render started"
  // means that frame is on screen AND playback was asked for, whichever came second. Not private:
  // the player listener and the AdMediaPlayer handle call it, and lint rejects synthetic accessors.
  internal fun maybeReportRenderStarted() {
    if (renderStartReported || !frameRendered) return
    if (exoPlayer?.playWhenReady != true) return
    renderStartReported = true
    onRenderedFirstFrameListener?.onRenderedFirstFrame()
  }

  fun getCurrentPosition(): Long {
    return if (prepared) adPlayer?.currentPosition ?: 0L else 0L
  }

  fun setAudioFocusRequest(focusGain: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (focusGain == AudioManager.AUDIOFOCUS_NONE) {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
      } else {
        val req =
            AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build())
                .build()
        audioFocusRequest = req
        audioManager.requestAudioFocus(req)
      }
    }
  }

  /** Releases the current player. The view stays usable for the next [setVideoUri]. */
  fun release() {
    removeCallbacks(orphanRelease)
    lastMeterUseMs = SystemClock.elapsedRealtime()
    exoPlayer?.let { player ->
      player.removeListener(playerListener)
      player.clearVideoTextureView(this)
      player.release()
    }
    adPlayer?.detach()
    exoPlayer = null
    adPlayer = null
    prepared = false
    buffering = false
    frameRendered = false
    renderStartReported = false
    videoWidth = 0
    videoHeight = 0
  }
}
