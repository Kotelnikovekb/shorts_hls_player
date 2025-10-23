import 'dart:typed_data';

import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'method_channel_impl.dart';
import 'types.dart';

abstract class ShortsPlatform extends PlatformInterface {
  ShortsPlatform() : super(token: _token);
  static final Object _token = Object();
  static ShortsPlatform _instance = MethodChannelShorts();

  static ShortsPlatform get instance => _instance;

  static set instance(ShortsPlatform i) {
    PlatformInterface.verifyToken(i, _token);
    _instance = i;
  }

  // core
  Future<void> init({ShortsInitConfig config = const ShortsInitConfig()});

  Future<void> append(List<String> urls, {bool replace = false});

  Future<void> disposeIndex(int index);

  Future<void> setCurrent(int index);

  Future<void> play(int index);

  Future<void> pause(int index);

  // quality
  Future<List<ShortsVariant>> getVariants(int index);

  Future<void> setQualityPreset(ShortsQuality preset);

  // preload
  Future<void> prime(int index);

  // meta
  Future<ShortsMeta> getMeta(int index);

  // view handle for iOS UiKitView
  Future<int> createView(int index); // returns viewId
  Stream<ShortsEvent> events();

  Future<Uint8List?> getThumbnail(int index);

  Future<void> togglePlayPause(int index);

  Future<void> setMuted(bool value);

  Future<void> setVolume(double value);

  Future<void> setLooping(bool value);

  Future<bool> isPaused(int index);

  Future<void> setProgressTracking({required bool enabled, int? intervalMs});
  void setMethodCallHandler(void Function(String method, Map args) handler);
  Future<void> primeSingle(int index);
  Future<Map<String, dynamic>?> getPlaybackInfo(int index);

  Future<void> configureCacheSize({int? maxCacheSizeMb});
  Future<String> getCacheState();
  Future<Map<String, dynamic>?> getCacheStats();
  Future<void> clearCache();
  Future<bool> isCached(String url);
  Future<int> getCachedBytes(String url);
  Future<bool> removeFromCache(String url);
  
  // Lifecycle management
  Future<void> onAppPaused();
  Future<void> onAppResumed();
  Future<void> forceSurfaceRefresh(int index);
}
