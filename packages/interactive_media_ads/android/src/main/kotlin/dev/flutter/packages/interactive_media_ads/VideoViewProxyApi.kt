// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package dev.flutter.packages.interactive_media_ads

import android.media.AudioManager
import android.os.Build

/**
 * ProxyApi implementation for [TextureVideoView].
 *
 * <p>This class handles instantiating [TextureVideoView] native object instances that are attached
 * to a Dart instance and handling method calls on the associated native class.
 */
class VideoViewProxyApi(override val pigeonRegistrar: ProxyApiRegistrar) :
    PigeonApiVideoView(pigeonRegistrar) {

  override fun pigeon_defaultConstructor(): TextureVideoView {
    val instance = TextureVideoView(pigeonRegistrar.context)
    instance.setOnPreparedListener { player: AdMediaPlayer -> onPrepared(instance, player) {} }
    instance.setOnErrorListener { player: AdMediaPlayer, what: Int, extra: Int ->
      onError(instance, player, what.toLong(), extra.toLong()) {}
    }
    instance.setOnCompletionListener { player: AdMediaPlayer -> onCompletion(instance, player) {} }
    instance.setOnRenderedFirstFrameListener { onRenderedFirstFrame(instance) {} }
    instance.setOnBufferingListener { buffering: Boolean -> onBuffering(instance, buffering) {} }
    return instance
  }

  override fun setVideoUri(pigeon_instance: TextureVideoView, uri: String?) {
    pigeon_instance.setVideoUri(uri)
  }

  override fun getCurrentPosition(pigeon_instance: TextureVideoView): Long {
    return pigeon_instance.getCurrentPosition()
  }

  override fun release(pigeon_instance: TextureVideoView) {
    pigeon_instance.release()
  }

  override fun setAudioFocusRequest(
      pigeon_instance: TextureVideoView,
      focusGain: AudioManagerAudioFocus
  ) {
    if (pigeonRegistrar.sdkIsAtLeast(Build.VERSION_CODES.O)) {
      pigeon_instance.setAudioFocusRequest(
          when (focusGain) {
            AudioManagerAudioFocus.GAIN -> AudioManager.AUDIOFOCUS_GAIN
            AudioManagerAudioFocus.GAIN_TRANSIENT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioManagerAudioFocus.GAIN_TRANSIENT_EXCLUSIVE ->
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            AudioManagerAudioFocus.GAIN_TRANSIENT_MAY_DUCK ->
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioManagerAudioFocus.NONE -> AudioManager.AUDIOFOCUS_NONE
          })
    }
  }
}
