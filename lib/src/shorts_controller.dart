import 'platform_interface.dart';
import 'types.dart';
import 'dart:async';

class ShortsController {
  final _p = ShortsPlatform.instance;
  final _eventsCtrl = StreamController<ShortsEvent>.broadcast();

  Stream<ShortsEvent> get events => _eventsCtrl.stream;

  Future<void> init() async {
    await _p.init();
    _p.events().listen(_eventsCtrl.add);
  }

  Future<void> append(List<Uri> urls) =>
      _p.append(urls.map((e) => e.toString()).toList());

  Future<void> prewarmAround(
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
}
