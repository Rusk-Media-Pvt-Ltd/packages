// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:flutter/cupertino.dart';

import 'interactive_media_ads_platform.dart';
import 'platform_companion_ad_slot.dart';

/// Object specifying creation parameters for creating a
/// [PlatformAdDisplayContainer].
///
/// Platform specific implementations can add additional fields by extending
/// this class.
///
/// This example demonstrates how to extend the
/// [PlatformAdDisplayContainerCreationParams] to provide additional platform
/// specific parameters.
///
/// When extending [PlatformAdDisplayContainerCreationParams] additional
/// parameters should always accept `null` or have a default value to prevent
/// breaking changes.
///
/// ```dart
/// class AndroidPlatformAdDisplayContainerCreationParams
///     extends PlatformAdDisplayContainerCreationParams {
///   AndroidPlatformAdDisplayContainerCreationParams._(
///     PlatformAdDisplayContainerCreationParams params, {
///     this.uri,
///   }) : super();
///
///   factory AndroidAdDisplayContainerCreationParams.fromPlatformAdDisplayContainerCreationParams(
///     PlatformAdDisplayContainerCreationParams params, {
///     Uri? uri,
///   }) {
///     return AndroidAdDisplayContainerCreationParams._(params, uri: uri);
///   }
///
///   final Uri? uri;
/// }
/// ```
@immutable
base class PlatformAdDisplayContainerCreationParams {
  /// Used by the platform implementation to create a new
  /// [PlatformAdDisplayContainer].
  const PlatformAdDisplayContainerCreationParams({
    this.key,
    required this.onContainerAdded,
    this.onAdRenderStarted,
    this.onAdProgress,
    this.onAdBuffering,
    this.layoutDirection = TextDirection.ltr,
    this.companionSlots = const <PlatformCompanionAdSlot>[],
  });

  /// Controls how one widget replaces another widget in the tree.
  ///
  /// See also:
  ///  * The discussions at [Key] and [GlobalKey].
  final Key? key;

  /// Invoked when the View that contains the ad has been added to the platform
  /// view hierarchy.
  final void Function(PlatformAdDisplayContainer container) onContainerAdded;

  /// Invoked when the first frame of the current creative is on screen and
  /// playback has begun, once per play or resume. On Android this precedes
  /// the IMA SDK's own `started` event, which lags the first frame by up to a
  /// few seconds. Never invoked on platforms that do not report it.
  final void Function()? onAdRenderStarted;

  /// Invoked with the player's real playhead while the current creative is
  /// playing, at the platform's progress-polling rate (250 ms on Android).
  /// Never invoked on platforms that do not report it.
  final void Function(Duration position, Duration duration)? onAdProgress;

  /// Invoked with `true` when the player of the current creative stalls for
  /// data after it was prepared, and `false` when it resumes. Never invoked on
  /// platforms that do not report it.
  final void Function(bool buffering)? onAdBuffering;

  /// The layout direction to use for the embedded AdDisplayContainer.
  final TextDirection layoutDirection;

  /// List of companion ad slots.
  final Iterable<PlatformCompanionAdSlot> companionSlots;
}

/// The interface for a platform implementation for a container in which to
/// display ads.
abstract base class PlatformAdDisplayContainer {
  /// Creates a new [PlatformAdDisplayContainer]
  factory PlatformAdDisplayContainer(
    PlatformAdDisplayContainerCreationParams params,
  ) {
    assert(
      InteractiveMediaAdsPlatform.instance != null,
      'A platform implementation for `interactive_media_ads` has not been set. '
      'Please ensure that an implementation of `InteractiveMediaAdsPlatform` '
      'has been set to `InteractiveMediaAdsPlatform.instance` before use. For '
      'unit testing, `InteractiveMediaAdsPlatform.instance` can be set with '
      'your own test implementation.',
    );
    final PlatformAdDisplayContainer implementation =
        InteractiveMediaAdsPlatform.instance!.createPlatformAdDisplayContainer(
          params,
        );
    return implementation;
  }

  /// Used by the platform implementation to create a new
  /// [PlatformAdDisplayContainer].
  ///
  /// Should only be used by platform implementations because they can't extend
  /// a class that only contains a factory constructor.
  @protected
  PlatformAdDisplayContainer.implementation(this.params);

  /// The parameters used to initialize the [PlatformAdDisplayContainer].
  final PlatformAdDisplayContainerCreationParams params;

  /// Builds the Widget that contains the native View.
  Widget build(BuildContext context);
}
