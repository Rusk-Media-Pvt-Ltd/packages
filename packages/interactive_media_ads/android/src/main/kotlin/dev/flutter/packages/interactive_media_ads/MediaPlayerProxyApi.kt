// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package dev.flutter.packages.interactive_media_ads

/**
 * ProxyApi implementation for [AdMediaPlayer].
 *
 * <p>This class may handle instantiating native object instances that are attached to a Dart
 * instance or handle method calls on the associated native class or an instance of that class.
 */
class MediaPlayerProxyApi(override val pigeonRegistrar: ProxyApiRegistrar) :
    PigeonApiMediaPlayer(pigeonRegistrar) {
  override fun getDuration(pigeon_instance: AdMediaPlayer): Long {
    return pigeon_instance.duration
  }

  override fun seekTo(pigeon_instance: AdMediaPlayer, mSec: Long) {
    pigeon_instance.seekTo(mSec)
  }

  override fun start(pigeon_instance: AdMediaPlayer) {
    pigeon_instance.start()
  }

  override fun pause(pigeon_instance: AdMediaPlayer) {
    pigeon_instance.pause()
  }

  override fun stop(pigeon_instance: AdMediaPlayer) {
    pigeon_instance.stop()
  }
}
