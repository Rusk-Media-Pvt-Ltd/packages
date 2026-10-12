// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package dev.flutter.packages.interactive_media_ads

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

// The Dart side drives this handle exactly as it drove android.media.MediaPlayer, so the
// MediaPlayer conventions it relies on must hold: a 0 duration until known, start/pause as
// playWhenReady, and a stop that leaves the player reusable.
class AdMediaPlayerTest {
  @Test
  fun durationIsZeroWhileUnknown() {
    val player = mock<ExoPlayer>()
    whenever(player.duration).thenReturn(C.TIME_UNSET)

    assertEquals(0L, AdMediaPlayer(player).duration)
  }

  @Test
  fun durationPassesThroughOnceKnown() {
    val player = mock<ExoPlayer>()
    whenever(player.duration).thenReturn(10_040L)

    assertEquals(10_040L, AdMediaPlayer(player).duration)
  }

  @Test
  fun startSetsPlayWhenReadyAndReportsBack() {
    val player = mock<ExoPlayer>()
    var started = false

    AdMediaPlayer(player) { started = true }.start()

    verify(player).playWhenReady = true
    assertTrue(started)
  }

  @Test
  fun pauseClearsPlayWhenReady() {
    val player = mock<ExoPlayer>()

    AdMediaPlayer(player).pause()

    verify(player).playWhenReady = false
  }

  @Test
  fun seekToAndStopPassThrough() {
    val player = mock<ExoPlayer>()
    val handle = AdMediaPlayer(player)

    handle.seekTo(2_500L)
    handle.stop()

    verify(player).seekTo(2_500L)
    verify(player).stop()
  }

  @Test
  fun aHandleWithoutAPlayerIsInert() {
    val handle = AdMediaPlayer(null)

    handle.start()
    handle.pause()
    handle.seekTo(1L)
    handle.stop()

    assertEquals(0L, handle.duration)
    assertEquals(0L, handle.currentPosition)
  }

  // Device 2026-10-09: the Dart side still held the handle of a player the view had released
  // and called start() on it, which reached a dead playback thread.
  @Test
  fun aDetachedHandleNoLongerReachesItsPlayer() {
    val player = mock<ExoPlayer>()
    val handle = AdMediaPlayer(player)

    handle.detach()
    handle.start()
    handle.pause()
    handle.seekTo(1L)
    handle.stop()

    verifyNoInteractions(player)
    assertEquals(0L, handle.duration)
  }
}
