// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package dev.flutter.packages.interactive_media_ads

import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MediaPlayerProxyApiTest {
  @Test
  fun getDuration() {
    val api = TestProxyApiRegistrar().getPigeonApiMediaPlayer()

    val instance = mock<AdMediaPlayer>()
    whenever(instance.duration).thenReturn(10_000L)

    assertEquals(10_000L, api.getDuration(instance))
  }

  @Test
  fun seekTo() {
    val api = TestProxyApiRegistrar().getPigeonApiMediaPlayer()

    val instance = mock<AdMediaPlayer>()
    api.seekTo(instance, 500L)

    verify(instance).seekTo(500L)
  }

  @Test
  fun start() {
    val api = TestProxyApiRegistrar().getPigeonApiMediaPlayer()

    val instance = mock<AdMediaPlayer>()
    api.start(instance)

    verify(instance).start()
  }

  @Test
  fun pause() {
    val api = TestProxyApiRegistrar().getPigeonApiMediaPlayer()

    val instance = mock<AdMediaPlayer>()
    api.pause(instance)

    verify(instance).pause()
  }

  @Test
  fun stop() {
    val api = TestProxyApiRegistrar().getPigeonApiMediaPlayer()

    val instance = mock<AdMediaPlayer>()
    api.stop(instance)

    verify(instance).stop()
  }
}
