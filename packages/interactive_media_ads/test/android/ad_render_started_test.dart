// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:flutter_test/flutter_test.dart';
import 'package:interactive_media_ads/src/android/android_ad_display_container.dart';
import 'package:interactive_media_ads/src/android/interactive_media_ads.g.dart'
    as ima;
import 'package:mockito/mockito.dart';

import 'ad_display_container_test.mocks.dart';

// The native player reports three things MediaPlayer never did: the first
// rendered frame, buffering, and (through the existing poll) a real position.
// This covers how the container forwards each, and that a dropped ad's player
// is released rather than left to start later.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late void Function(ima.VideoAdPlayer, ima.AdMediaInfo, ima.AdPodInfo)
  loadAdCallback;
  late Future<void> Function(ima.VideoView, ima.MediaPlayer) onPreparedCallback;
  late void Function(ima.VideoView) onRenderedFirstFrameCallback;
  late void Function(ima.VideoView, bool) onBufferingCallback;
  late void Function(ima.VideoAdPlayer, ima.AdMediaInfo) playAdCallback;
  late void Function(ima.VideoAdPlayer, ima.AdMediaInfo) stopAdCallback;
  late void Function(ima.VideoAdPlayer) releaseCallback;
  late MockVideoView currentVideoView;

  setUp(() {
    ima.PigeonOverrides.pigeon_reset();
    ima.PigeonOverrides.frameLayout_new = () => MockFrameLayout();
    ima.PigeonOverrides.videoView_new =
        ({
          dynamic onError,
          Future<void> Function(ima.VideoView, ima.MediaPlayer)? onPrepared,
          dynamic onCompletion,
          void Function(ima.VideoView)? onRenderedFirstFrame,
          void Function(ima.VideoView, bool)? onBuffering,
        }) {
          onPreparedCallback = onPrepared!;
          onRenderedFirstFrameCallback = onRenderedFirstFrame!;
          onBufferingCallback = onBuffering!;
          currentVideoView = MockVideoView();
          when(
            currentVideoView.getCurrentPosition(),
          ).thenAnswer((_) async => 0);
          return currentVideoView;
        };
    ima.PigeonOverrides.imaSdkFactory_createAdDisplayContainer =
        (_, __) async => MockAdDisplayContainer();
    ima.PigeonOverrides.videoAdPlayer_new =
        ({
          required dynamic addCallback,
          required void Function(
            ima.VideoAdPlayer,
            ima.AdMediaInfo,
            ima.AdPodInfo,
          )
          loadAd,
          required dynamic pauseAd,
          required void Function(ima.VideoAdPlayer, ima.AdMediaInfo) playAd,
          required void Function(ima.VideoAdPlayer) release,
          required dynamic removeCallback,
          required void Function(ima.VideoAdPlayer, ima.AdMediaInfo) stopAd,
        }) {
          loadAdCallback = loadAd;
          playAdCallback = playAd;
          stopAdCallback = stopAd;
          releaseCallback = release;
          return MockVideoAdPlayer();
        };
    ima.PigeonOverrides.videoProgressUpdate_new =
        ({required int currentTimeMs, required int durationMs}) =>
            MockVideoProgressUpdate();
  });

  test('the first rendered frame reaches onAdRenderStarted', () {
    var renderStarts = 0;
    // Held in a local: the plugin keeps only a WeakReference to it.
    final container = AndroidAdDisplayContainer(
      AndroidAdDisplayContainerCreationParams(
        onContainerAdded: (_) {},
        onAdRenderStarted: () => renderStarts++,
      ),
    );

    onRenderedFirstFrameCallback(currentVideoView);

    expect(renderStarts, 1);
    expect(container.params.onAdRenderStarted, isNotNull);
  });

  test('a container without the callback ignores the first frame', () {
    final container = AndroidAdDisplayContainer(
      AndroidAdDisplayContainerCreationParams(onContainerAdded: (_) {}),
    );

    onRenderedFirstFrameCallback(currentVideoView);

    expect(container.params.onAdRenderStarted, isNull);
  });

  test('a stall is reported to the SDK once, its end is not', () {
    final container = AndroidAdDisplayContainer(
      AndroidAdDisplayContainerCreationParams(onContainerAdded: (_) {}),
    );
    final callback = MockVideoAdPlayerCallback();
    container.videoAdPlayerCallbacks.add(callback);
    final ima.AdMediaInfo mediaInfo = MockAdMediaInfo();
    loadAdCallback(MockVideoAdPlayer(), mediaInfo, MockAdPodInfo());

    onBufferingCallback(currentVideoView, true);
    onBufferingCallback(currentVideoView, false);

    verify(callback.onBuffering(mediaInfo)).called(1);
  });

  test('both buffering edges reach the host, with or without a loaded ad', () {
    final edges = <bool>[];
    final container = AndroidAdDisplayContainer(
      AndroidAdDisplayContainerCreationParams(
        onContainerAdded: (_) {},
        onAdBuffering: edges.add,
      ),
    );
    final callback = MockVideoAdPlayerCallback();
    container.videoAdPlayerCallbacks.add(callback);

    onBufferingCallback(currentVideoView, true);
    onBufferingCallback(currentVideoView, false);

    expect(edges, <bool>[true, false]);
    // The SDK is told only once an ad is loaded.
    verifyNever(callback.onBuffering(any));
  });

  test('a stall with no ad loaded is dropped', () {
    final container = AndroidAdDisplayContainer(
      AndroidAdDisplayContainerCreationParams(onContainerAdded: (_) {}),
    );
    final callback = MockVideoAdPlayerCallback();
    container.videoAdPlayerCallbacks.add(callback);

    onBufferingCallback(currentVideoView, true);

    verifyNever(callback.onBuffering(any));
  });

  test('the progress poll hands the real position to onAdProgress', () async {
    final reports = <(Duration, Duration)>[];
    final container = AndroidAdDisplayContainer(
      AndroidAdDisplayContainerCreationParams(
        onContainerAdded: (_) {},
        onAdProgress: (Duration position, Duration duration) =>
            reports.add((position, duration)),
      ),
    );
    final ima.AdMediaInfo mediaInfo = MockAdMediaInfo();
    loadAdCallback(MockVideoAdPlayer(), mediaInfo, MockAdPodInfo());
    playAdCallback(MockVideoAdPlayer(), mediaInfo);
    when(currentVideoView.getCurrentPosition()).thenAnswer((_) async => 1250);

    final mockMediaPlayer = MockMediaPlayer();
    when(mockMediaPlayer.getDuration()).thenAnswer((_) async => 10000);
    await onPreparedCallback(currentVideoView, mockMediaPlayer);

    // One 250 ms poll is enough; the extra time proves it keeps reporting.
    await Future<void>.delayed(const Duration(milliseconds: 600));
    releaseCallback(MockVideoAdPlayer());

    expect(reports, isNotEmpty);
    expect(reports.first.$1, const Duration(milliseconds: 1250));
    expect(reports.first.$2, const Duration(seconds: 10));
    expect(container.params.onAdProgress, isNotNull);
  });

  test('stopAd releases the player of the view it retires', () {
    final container = AndroidAdDisplayContainer(
      AndroidAdDisplayContainerCreationParams(onContainerAdded: (_) {}),
    );
    final ima.AdMediaInfo mediaInfo = MockAdMediaInfo();
    loadAdCallback(MockVideoAdPlayer(), mediaInfo, MockAdPodInfo());
    final retired = currentVideoView;

    stopAdCallback(MockVideoAdPlayer(), mediaInfo);

    // Device 2026-10-09, 150 kbps: the SDK's media-load timeout dropped the
    // ad, the download finished later and the old player started on its own,
    // playing audio over the resumed content.
    verify(retired.release()).called(1);
    expect(currentVideoView, isNot(same(retired)));
    expect(container.videoAdPlayerCallbacks, isEmpty);
  });

  test('release at the end of the break releases the player too', () {
    AndroidAdDisplayContainer(
      AndroidAdDisplayContainerCreationParams(onContainerAdded: (_) {}),
    );
    final ima.AdMediaInfo mediaInfo = MockAdMediaInfo();
    loadAdCallback(MockVideoAdPlayer(), mediaInfo, MockAdPodInfo());
    final retired = currentVideoView;

    releaseCallback(MockVideoAdPlayer());

    verify(retired.release()).called(1);
  });
}
