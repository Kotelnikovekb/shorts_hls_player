import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'platform_interface.dart';
import 'types.dart';
import 'dart:async';

class ShortsController with ChangeNotifier {
  final _p = ShortsPlatform.instance;
  final _eventsCtrl = StreamController<ShortsEvent>.broadcast();

  bool _isPaused = true;
  bool _isMuted = false;
  double _volume = 1.0;
  bool _looping = false;

  bool get isPaused => _isPaused;

  bool get isMuted => _isMuted;

  double get volume => _volume;

  bool get looping => _looping;

  bool muted = false;
  ShortsQuality qualityPreset = ShortsQuality.auto;
  bool progressEnabled = false;
  Duration progressInterval = const Duration(milliseconds: 500);
  bool _initialized = false;


  Stream<ShortsEvent> get events => _eventsCtrl.stream;

  Future<void> init() async {
    if (_initialized) return;

    _p.events().listen(_eventsCtrl.add);

    _p.setMethodCallHandler((method, args) {
      switch (method) {
        case 'onWatched':
          // здесь можешь дергать свой Stream/Callback
          break;
        case 'onProgress':
          // тут можно пробрасывать свой ProgressEvent
          break;
      }
    });

    // единый «толстый» init с конфигом
    await _p.init(
      config: ShortsInitConfig(
        looping: looping,
        muted: muted,
        volume: volume,
        quality: qualityPreset,
        progressEnabled: progressEnabled,
        progressInterval: progressInterval,
      ),
    );

    // await _p.setQualityPreset(qualityPreset);

    _initialized = true;
  }

  Future<Uint8List?> getThumbnail(int index) => _p.getThumbnail(index);

  Future<void> append(List<Uri> urls) =>
      _p.append(urls.map((e) => e.toString()).toList());

/*  Future<void> prewarmAround(
    int index, {
    int forward = 1,
    int backward = 1,
  }) async {
    await _p.prime(index);
    for (int i = 1; i <= forward; i++) {
      await _p.prime(index + i);
    }
    for (int i = 1; i <= backward; i++) {
      await _p.prime(index - i);
    }
  }*/

  Future<void> prewarmAround(
      int index, {
        int forward = 1,
        int backward = 1,
      }) async {
    // helper: безопасно вызвать prime, игнорируя невалидные индексы/ошибки
    Future<void> _safePrime(int i) async {
      if (i < 0) return; // НЕ шлём отрицательные индексы -> не ловим INVALID_INDEX
      try {
        await _p.prime(i); // твой method_channel_impl.dart занимается нативом
      } on PlatformException {
        // best-effort прелоад: молча игнорируем
      } catch (_) {
        // на всякий случай гасим и прочее
      }
    }

    // текущий
    await _safePrime(index);

    // вперёд
    final futures = <Future<void>>[];
    for (int i = 1; i <= forward; i++) {
      futures.add(_safePrime(index + i));
    }
    // назад
    for (int i = 1; i <= backward; i++) {
      final prev = index - i;
      if (prev < 0) break; // дальше только отрицательные — сразу выходим
      futures.add(_safePrime(prev));
    }

    await Future.wait(futures);
  }

  Future<void> onActive(int index) async {
    await _p.setCurrent(index);
    await _p.play(index);
  }

  Future<void> onInactive(int index) async {
    await _p.pause(index);
  }

  Future<void> disposeIndex(int index) => _p.disposeIndex(index);

  Future<void> setQualityPreset(ShortsQuality q) => _p.setQualityPreset(q);

  Future<List<ShortsVariant>> getVariants(int index) => _p.getVariants(index);

  Future<ShortsMeta> getMeta(int index) => _p.getMeta(index);

  Future<void> play(int index) async {
    await _p.play(index);
    _isPaused = false;
    notifyListeners();
  }

  Future<void> pause(int index) async {
    await _p.pause(index);
    _isPaused = true;
    notifyListeners();
  }

  Future<void> togglePlayPause(int index) async {
    await _p.togglePlayPause(index);
    // спросим фактическое состояние
    _isPaused = await _p.isPaused(index);
    notifyListeners();
  }

  Future<void> setMuted(bool value) async {
    await _p.setMuted(value);
    _isMuted = value;
    notifyListeners();
  }

  Future<void> setVolume(double value) async {
    await _p.setVolume(value);
    _volume = value.clamp(0, 1);
    notifyListeners();
  }

  Future<void> setLooping(bool value) async {
    await _p.setLooping(value);
    _looping = value;
    notifyListeners();
  }

  Future<bool> isPausedAt(int index) => _p.isPaused(index);

  Future<void> setProgressTracking(
      {required bool enabled, Duration? interval}) async {
    await _p.setProgressTracking(
        enabled: enabled, intervalMs: interval?.inMilliseconds);
  }

  bool _methodHandlerAttached = false;

  void _attachMethodHandlerIfNeeded() {
    if (_methodHandlerAttached) return;
    _p.setMethodCallHandler((method, args) {
      switch (method) {
        case 'onWatched':
          final index = (args['index'] as num?)?.toInt() ?? -1;
          final url = args['url'] as String?;
          if (index >= 0 && url != null) {
            // можно дернуть свой notifier/stream
            // print('onWatched $index $url');
          }
          break;
        case 'onProgress':
          final index = (args['index'] as num?)?.toInt() ?? -1;
          final url = args['url'] as String?;
          final pos = (args['positionMs'] as num?)?.toInt() ?? -1;
          final dur = (args['durationMs'] as num?)?.toInt() ?? -1;
          final buf = (args['bufferedMs'] as num?)?.toInt() ?? -1;
          if (index >= 0 && url != null && pos >= 0) {
            // тут можно пробрасывать наружу свой Stream<ProgressEvent>
            // print('onProgress $index $pos/$dur (+$buf)');
          }
          break;
      }
    });
    _methodHandlerAttached = true;
  }
}
