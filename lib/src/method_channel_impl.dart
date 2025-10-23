import 'dart:async';
import 'package:flutter/services.dart';
import 'platform_interface.dart';
import 'types.dart';

class MethodChannelShorts extends ShortsPlatform {
  static const _ch = MethodChannel('shorts_hls_player/methods');
  static const _ev = EventChannel('shorts_hls_player/events');
  Stream<ShortsEvent>? _cached;

  @override
  Future<void> init(
      {ShortsInitConfig config = const ShortsInitConfig()}) async {
    await _ch.invokeMethod('init', config.toMap());
  }

  @override
  Future<void> append(List<String> urls, {bool replace = false}) =>
      _ch.invokeMethod('appendUrls', {'urls': urls, 'replace': replace});

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
    _cached ??= _ev
        .receiveBroadcastStream()
        .map((e) => parseShortsEvent(e as Map));
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
    _ch.setMethodCallHandler((call) async {
      final args =
          (call.arguments as Map?)?.cast<String, Object?>() ?? const {};
      handler(call.method, args);
    });
  }

  @override
  Future<void> togglePlayPause(int index) =>
      _ch.invokeMethod('togglePlayPause', {'index': index});

  @override
  Future<void> setMuted(bool value) =>
      _ch.invokeMethod('setMuted', {'muted': value});

  @override
  Future<void> setVolume(double value) =>
      _ch.invokeMethod('setVolume', {'volume': value});

  @override
  Future<void> setLooping(bool value) =>
      _ch.invokeMethod('setLooping', {'looping': value});

  @override
  Future<bool> isPaused(int index) async =>
      (await _ch.invokeMethod('isPaused', {'index': index})) as bool;

  @override
  Future<void> setProgressTracking({required bool enabled, int? intervalMs}) =>
      _ch.invokeMethod('setProgressTracking', {
        'enabled': enabled,
        if (intervalMs != null) 'intervalMs': intervalMs
      });

  @override
  Future<void> primeSingle(int index) =>
      _ch.invokeMethod('prime', {'index': index});

  @override
  Future<Map<String, dynamic>?> getPlaybackInfo(int index) async {
    final res = await _ch.invokeMethod<Map<dynamic, dynamic>>(
      'getPlaybackInfo',
      {'index': index},
    );
    return res?.map((k, v) => MapEntry(k.toString(), v));
  }

  @override
  Future<void> configureCacheSize({int? maxCacheSizeMb}) =>
      _ch.invokeMethod('configureCacheSize', {
        if (maxCacheSizeMb != null) 'maxCacheSizeMb': maxCacheSizeMb
      });

  @override
  Future<String> getCacheState() async =>
      (await _ch.invokeMethod<String>('getCacheState')) ?? 'UNINITIALIZED';

  @override
  Future<Map<String, dynamic>?> getCacheStats() async {
    final res = await _ch.invokeMethod<Map<dynamic, dynamic>>('getCacheStats');
    return res?.map((k, v) => MapEntry(k.toString(), v));
  }

  @override
  Future<void> clearCache() => _ch.invokeMethod('clearCache');

  @override
  Future<bool> isCached(String url) async =>
      (await _ch.invokeMethod<bool>('isCached', {'url': url})) ?? false;

  @override
  Future<int> getCachedBytes(String url) async =>
      (await _ch.invokeMethod<int>('getCachedBytes', {'url': url})) ?? 0;

  @override
  Future<bool> removeFromCache(String url) async =>
      (await _ch.invokeMethod<bool>('removeFromCache', {'url': url})) ?? false;
  
  @override
  Future<void> onAppPaused() => _ch.invokeMethod('onAppPaused');
  
  @override
  Future<void> onAppResumed() => _ch.invokeMethod('onAppResumed');
  
  @override
  Future<void> forceSurfaceRefresh(int index) => 
      _ch.invokeMethod('forceSurfaceRefresh', {'index': index});
}
