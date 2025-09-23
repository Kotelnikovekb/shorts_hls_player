import 'dart:async';
import 'package:flutter/services.dart';
import 'platform_interface.dart';
import 'types.dart';

class MethodChannelShorts extends ShortsPlatform {
  static const _ch = MethodChannel('shorts_hls_player/methods');
  static const _ev = EventChannel('shorts_hls_player/events');
  Stream<ShortsEvent>? _cached;
  void Function(String, Map)? _mh;


  @override
  Future<void> init({ShortsInitConfig config = const ShortsInitConfig()}) async {
    await _ch.invokeMethod('init', config.toMap());
  }

  @override
  Future<void> append(List<String> urls) =>
      _ch.invokeMethod('appendUrls', {'urls': urls});

  @override
  Future<void> disposeIndex(int index) =>
      _ch.invokeMethod('disposeIndex', {'index': index});

  @override
  Future<void> setCurrent(int index) =>
      _ch.invokeMethod('setCurrent', {'index': index});

  @override
  Future<void> play(int index) => _ch.invokeMethod('play', {'index': index});

  @override
  Future<void> pause(int index) => _ch.invokeMethod('pause', {'index': index});

  @override
  Future<void> prime(int index) => _ch.invokeMethod('prime', {'index': index});

  @override
  Future<void> setQualityPreset(ShortsQuality preset) =>
      _ch.invokeMethod('setQualityPreset', {'preset': preset.name});

  @override
  Future<List<ShortsVariant>> getVariants(int index) async {
    final list =
        await _ch.invokeListMethod<Map>('getVariants', {'index': index}) ?? [];
    return list
        .map((m) => ShortsVariant(
              height: m['height'] as int?,
              width: m['width'] as int?,
              bitrate: m['bitrate'] as int?,
              label: m['label'] as String? ?? 'Auto',
            ))
        .toList();
  }

  @override
  Future<ShortsMeta> getMeta(int index) async {
    final m = await _ch
            .invokeMapMethod<String, Object?>('getMeta', {'index': index}) ??
        {};
    return ShortsMeta(
      durationMs: m['durationMs'] as int?,
      width: m['width'] as int?,
      height: m['height'] as int?,
      fps: (m['fps'] as num?)?.toDouble(),
      avgBitrate: m['avgBitrate'] as int?,
    );
  }

  @override
  Future<int> createView(int index) async =>
      (await _ch.invokeMethod('createView', {'index': index})) as int;

  @override
  Stream<ShortsEvent> events() {
    _cached ??= _ev.receiveBroadcastStream().map((e) {
      final m = (e as Map).cast<String, Object?>();
      final type = m['type'];
      final idx = (m['index'] as num?)?.toInt() ?? -1;
      switch (type) {
        case 'ready':
          return OnReady(idx);
        case 'bufferingStart':
          return OnBufferingStart(idx);
        case 'bufferingEnd':
          return OnBufferingEnd(idx);
        case 'stall':
          return OnStall(idx);
        case 'error':
          return OnError(idx, (m['message'] as String?) ?? 'error');
        case 'progress':
          return OnProgress(idx, (m['posMs'] as num?)?.toInt() ?? 0,
              (m['durMs'] as num?)?.toInt());
        default:
          return OnError(idx, 'unknown_event');
      }
    });
    return _cached!;
  }

  @override
  Future<Uint8List?> getThumbnail(int index) async {
    final bytes =
        await _ch.invokeMethod<Uint8List>('getThumbnail', {'index': index});
    return bytes;
  }

  @override
  void setMethodCallHandler(void Function(String, Map args) handler) {
    _mh = handler;
    _ch.setMethodCallHandler((call) async {
      final args = (call.arguments as Map?)?.cast<String, Object?>() ?? const {};
      handler(call.method, args);
    });
  }


  @override Future<void> togglePlayPause(int index) => _ch.invokeMethod('togglePlayPause', {'index': index});
  @override Future<void> setMuted(bool value) => _ch.invokeMethod('setMuted', {'muted': value});
  @override Future<void> setVolume(double value) => _ch.invokeMethod('setVolume', {'volume': value});
  @override Future<void> setLooping(bool value) => _ch.invokeMethod('setLooping', {'looping': value});
  @override Future<bool> isPaused(int index) async => (await _ch.invokeMethod('isPaused', {'index': index})) as bool;

  @override Future<void> setProgressTracking({required bool enabled, int? intervalMs}) =>
      _ch.invokeMethod('setProgressTracking', {'enabled': enabled, if (intervalMs != null) 'intervalMs': intervalMs});

  @override Future<void> primeSingle(int index) => _ch.invokeMethod('prime', {'index': index});
}
