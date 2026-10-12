// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package dev.flutter.packages.interactive_media_ads

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

// Every ad unit starts on the ladder's lowest rung (estimate 0 fits nothing) and only the second
// segment sees what the shared meter remembers. The shared meter must still receive every
// transfer, or units after the first would have no history to adapt from.
@OptIn(UnstableApi::class)
class ColdStartBandwidthMeterTest {
  private val sharedListener = mock<TransferListener>()
  private val shared =
      mock<BandwidthMeter>().also {
        whenever(it.bitrateEstimate).thenReturn(5_000_000L)
        whenever(it.transferListener).thenReturn(sharedListener)
      }
  private val source = mock<DataSource>()
  private val spec = mock<DataSpec>()

  @Test
  fun readsZeroUntilTheFirstTransferEnds() {
    val meter = ColdStartBandwidthMeter(shared)

    assertEquals(0L, meter.bitrateEstimate)
    meter.onTransferStart(source, spec, true)
    meter.onBytesTransferred(source, spec, true, 100_000)
    assertEquals(0L, meter.bitrateEstimate)

    meter.onTransferEnd(source, spec, true)

    assertEquals(5_000_000L, meter.bitrateEstimate)
  }

  @Test
  fun forwardsEveryTransferToTheSharedMeter() {
    val meter = ColdStartBandwidthMeter(shared)

    meter.onTransferInitializing(source, spec, true)
    meter.onTransferStart(source, spec, true)
    meter.onBytesTransferred(source, spec, true, 4_096)
    meter.onTransferEnd(source, spec, true)

    verify(sharedListener).onTransferInitializing(source, spec, true)
    verify(sharedListener).onTransferStart(source, spec, true)
    verify(sharedListener).onBytesTransferred(source, spec, true, 4_096)
    verify(sharedListener).onTransferEnd(source, spec, true)
  }

  @Test
  fun eventListenersGoToTheSharedMeterSoReleaseUnregistersThem() {
    val meter = ColdStartBandwidthMeter(shared)
    val listener = mock<BandwidthMeter.EventListener>()
    val handler = mock<android.os.Handler>()

    meter.addEventListener(handler, listener)
    meter.removeEventListener(listener)

    verify(shared).addEventListener(handler, listener)
    verify(shared).removeEventListener(listener)
    assertSame(meter, meter.transferListener)
  }
}
