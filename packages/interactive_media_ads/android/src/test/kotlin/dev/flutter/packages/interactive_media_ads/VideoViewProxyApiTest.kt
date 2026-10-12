// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package dev.flutter.packages.interactive_media_ads

import android.media.AudioManager
import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class VideoViewProxyApiTest {
  @Test
  fun setVideoUri() {
    val api = TestProxyApiRegistrar().getPigeonApiVideoView()

    val instance = mock<TextureVideoView>()
    api.setVideoUri(instance, "https://example.com/ad.m3u8")

    verify(instance).setVideoUri("https://example.com/ad.m3u8")
  }

  @Test
  fun getCurrentPosition() {
    val api = TestProxyApiRegistrar().getPigeonApiVideoView()

    val instance = mock<TextureVideoView>()
    whenever(instance.getCurrentPosition()).thenReturn(1250L)

    assertEquals(1250L, api.getCurrentPosition(instance))
  }

  @Test
  fun release() {
    val api = TestProxyApiRegistrar().getPigeonApiVideoView()

    val instance = mock<TextureVideoView>()
    api.release(instance)

    verify(instance).release()
  }

  @Test
  fun setAudioFocusRequest() {
    val api = TestProxyApiRegistrar().getPigeonApiVideoView()

    val instance = mock<TextureVideoView>()
    api.setAudioFocusRequest(instance, AudioManagerAudioFocus.GAIN)

    verify(instance).setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN)
  }
}
