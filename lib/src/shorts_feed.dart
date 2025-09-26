import 'dart:async';
import 'dart:math';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import '../shorts_hls_player.dart';
import 'platform_interface.dart';

class PreloadWindow {
  final int fwd;
  final int back;

  const PreloadWindow({this.fwd = 1, this.back = 1});
}

class ScrollPrewarm {
  /// На сколько «долей» страницы нужно сдвинуться, чтобы начать прогрев целевой страницы
  final double triggerDelta;

  /// Порог «быстрого свайпа» в пикселях/мс
  final double fastSwipeSpeed;

  /// Сколько дополнительно прогревать при быстром свайпе (в направлении)
  final int extraOnFast;

  const ScrollPrewarm({
    this.triggerDelta = 0.30,
    this.fastSwipeSpeed = 0.8,
    this.extraOnFast = 1,
  });
}

class FeedOverlayState {
  final bool buffering;
  final String? error;
  final int positionMs;
  final int durationMs;
  final double scrollSpeedPxPerMs;
  final int direction; // -1 вверх, +1 вниз, 0
  final bool thumbnailLoading;
  final bool hasThumbnail;

  String get positionLabel {
    final d = Duration(milliseconds: positionMs);
    return '${(d.inMinutes % 60).toString().padLeft(2, '0')}:${(d.inSeconds % 60).toString().padLeft(2, '0')}';
  }

  String get durationLabel {
    if (durationMs <= 0) return '--:--';
    final d = Duration(milliseconds: durationMs);
    return '${(d.inMinutes % 60).toString().padLeft(2, '0')}:${(d.inSeconds % 60).toString().padLeft(2, '0')}';
  }

  const FeedOverlayState({
    required this.buffering,
    required this.error,
    required this.positionMs,
    required this.durationMs,
    required this.scrollSpeedPxPerMs,
    required this.direction,
    required this.thumbnailLoading,
    required this.hasThumbnail,
  });


  FeedOverlayState copyWith({
    bool? buffering,
    String? error,
    int? positionMs,
    int? durationMs,
    double? scrollSpeedPxPerMs,
    int? direction,
    bool? thumbnailLoading,
    bool? hasThumbnail,
  }) {
    return FeedOverlayState(
      buffering: buffering ?? this.buffering,
      error: error ?? this.error,
      positionMs: positionMs ?? this.positionMs,
      durationMs: durationMs ?? this.durationMs,
      scrollSpeedPxPerMs: scrollSpeedPxPerMs ?? this.scrollSpeedPxPerMs,
      direction: direction ?? this.direction,
      thumbnailLoading: thumbnailLoading ?? this.thumbnailLoading,
      hasThumbnail: hasThumbnail ?? this.hasThumbnail,
    );
  }
}

typedef FeedOverlayBuilder = Widget Function(
  BuildContext context,
  int index,
  FeedOverlayState state,
);

class ShortsFeed extends StatefulWidget {
  final List<Uri> urls;
  final ShortsController? controller;
  final PreloadWindow preloadWindow;
  final ScrollPrewarm scrollPrewarm;
  final ShortsQuality qualityPreset;
  final bool showThumbnailsWhileBuffering;
  final FeedOverlayBuilder? overlayBuilder;
  final ValueChanged<int>? onPageChanged;
  final BoxFit? fit;
  final bool adaptiveFit;
  final double nearSquare;

  const ShortsFeed({
    super.key,
    required this.urls,
    this.controller,
    this.preloadWindow = const PreloadWindow(),
    this.scrollPrewarm = const ScrollPrewarm(),
    this.qualityPreset = ShortsQuality.auto,
    this.showThumbnailsWhileBuffering = true,
    this.overlayBuilder,
    this.onPageChanged,
    this.fit,
    this.adaptiveFit = true,
    this.nearSquare = 1.1,
  });

  @override
  State<ShortsFeed> createState() => _ShortsFeedState();
}

class _ShortsFeedState extends State<ShortsFeed> {
  late final ShortsController _ctrl = widget.controller ?? ShortsController();
  final _pageCtrl = PageController();
  StreamSubscription<ShortsEvent>? _sub;

  int _current = 0;
  bool _buffering = false;
  String? _error;
  int _posMs = 0, _durMs = 0;

  // скролл-метрики
  double _lastPixels = 0;
  DateTime? _lastTs;
  double _speedPxPerMs = 0.0;
  double _lastBucket = -999;

  // превью-кэш
  final Map<int, ImageProvider> _thumbs = {};
  final Set<int> _thumbLoading = {};

  @override
  void initState() {
    super.initState();
    _bootstrap();
    _pageCtrl.addListener(_onScroll);
  }

  @override
  void dispose() {
    _pageCtrl.removeListener(_onScroll);
    _pageCtrl.dispose();
    _sub?.cancel();
    if (widget.controller == null) {
      // если мы создали контроллер сами — его жизнь управляет платформа
    }
    super.dispose();
  }

  Future<void> _bootstrap() async {
    await _ctrl.init();
    await _ctrl.setQualityPreset(widget.qualityPreset);
    _sub = _ctrl.events.listen(_onEvent);

    await _ctrl.append(widget.urls);
    // прогрев окна вокруг 0
    await _ctrl.prewarmAround(0,
        forward: widget.preloadWindow.fwd, backward: widget.preloadWindow.back);
    unawaited(_ctrl.ensureMetadata(0));
    unawaited(_ctrl.ensureMetadata(0 + 1));
    await _ctrl.onActive(0);
    // прогрев превью
    _ensureThumb(0);
    for (int i = 1; i <= widget.preloadWindow.fwd; i++) {
      _ensureThumb(i);
    }
    setState(() {});
  }

  void _onEvent(ShortsEvent e) {
    if (e is OnBufferingStart) {
      if (e.index == _current) setState(() => _buffering = true);
    } else if (e is OnBufferingEnd) {
      if (e.index == _current) setState(() => _buffering = false);
    } else if (e is OnError) {
      if (e.index == _current) setState(() => _error = e.message);
    } else if (e is OnProgress && e.index == _current) {
      setState(() {
        _posMs = e.posMs;
        _durMs = e.durMs ?? _durMs;
      });
    }
  }

  void _onScroll() {
    final metrics = _pageCtrl.position;
    final now = DateTime.now();
    final pixels = metrics.pixels;
    final dtMs = _lastTs == null
        ? 0.0
        : max(1.0, now.difference(_lastTs!).inMilliseconds.toDouble());
    final dy = pixels - _lastPixels;
    _speedPxPerMs = dtMs > 0 ? (dy.abs() / dtMs) : 0.0;
    _lastPixels = pixels;
    _lastTs = now;

    final page = _pageCtrl.page ?? _current.toDouble();
    final delta = (page - _current).abs();
    final dir = (page - _current).sign.toInt();

    if (dir == 0) return;
    if (delta < widget.scrollPrewarm.triggerDelta) return;

    // дроссель — нотифицируем не чаще, чем раз на 0.1 страницы
    final bucket = (page * 10).round() / 10.0;
    if (bucket == _lastBucket) return;
    _lastBucket = bucket;

    final target = _current + dir;
    _prewarmDirection(
        dir, target, _speedPxPerMs >= widget.scrollPrewarm.fastSwipeSpeed);
  }

  Future<void> _prewarmDirection(int dir, int target, bool fast) async {
    if (target < 0 || target >= widget.urls.length) return;

    // прогреваем целевую
    await _ctrl.prewarmAround(target, forward: 0, backward: 0);

    unawaited(_ctrl.ensureMetadata(target));
    unawaited(_ctrl.ensureMetadata(target + 1));
    if (target > 0) unawaited(_ctrl.ensureMetadata(target - 1));
    _ensureThumb(target);

    // базовое окно
    final baseFwd = widget.preloadWindow.fwd;
    final baseBack = widget.preloadWindow.back;

    // в направлении: +1..extraOnFast если fast
    final extra = fast ? max(1, widget.scrollPrewarm.extraOnFast) : 1;
    for (int k = 1; k <= extra; k++) {
      final idx = target + dir * k;
      if (idx >= 0 && idx < widget.urls.length) {
        await _ctrl.prewarmAround(target, forward: 0, backward: 0);

        unawaited(_ctrl.ensureMetadata(target));
        unawaited(_ctrl.ensureMetadata(target + 1));
        if (target > 0) unawaited(_ctrl.ensureMetadata(target - 1));

        _ensureThumb(idx);
      }
    }

    // вне направления — по базовым настройкам окна
    for (int k = 1; k <= (dir > 0 ? baseBack : baseFwd); k++) {
      final idx = _current + (dir > 0 ? -k : k);
      if (idx >= 0 && idx < widget.urls.length) {
        await _ctrl.prewarmAround(target, forward: 0, backward: 0);

        unawaited(_ctrl.ensureMetadata(target));
        unawaited(_ctrl.ensureMetadata(target + 1));
        if (target > 0) unawaited(_ctrl.ensureMetadata(target - 1));

        _ensureThumb(idx);
      }
    }
  }

  Future<void> _changePage(int i) async {
    await _ctrl.onInactive(_current);
    _current = i;
    await _ctrl.onActive(i);
    await _ctrl.prewarmAround(i,
        forward: widget.preloadWindow.fwd, backward: widget.preloadWindow.back);

    unawaited(_ctrl.ensureMetadata(i));
    unawaited(_ctrl.ensureMetadata(i + 1));
    if (i > 0) unawaited(_ctrl.ensureMetadata(i - 1));

    _ensureThumb(i);
    widget.onPageChanged?.call(i);
    setState(() {
      _buffering = false;
      _error = null;
      _posMs = 0;
    });
  }

  Future<void> _ensureThumb(int index) async {
    if (!widget.showThumbnailsWhileBuffering) return;
    if (_thumbs.containsKey(index) || _thumbLoading.contains(index)) return;

    _thumbLoading.add(index); // <— пометили, что грузим
    if (mounted) setState(() {}); // чтобы overlay увидел thumbnailLoading=true

    try {
      final bytes = await _ctrl.getThumbnail(index);
      if (bytes != null && bytes.isNotEmpty) {
        _thumbs[index] = MemoryImage(bytes);
      }
    } catch (_) {
      // игнорируем — просто не будет превью
    } finally {
      _thumbLoading.remove(index);
      if (mounted) setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    if (widget.urls.isEmpty) {
      return const Center(child: Text('Нет ссылок'));
    }
    return NotificationListener<ScrollNotification>(
      onNotification: (n) {
        // параллельный канал для скоростей: уже считаем в _onScroll
        return false;
      },
      child: PageView.builder(
        key: widget.key,
        controller: _pageCtrl,
        scrollDirection: Axis.vertical,
        itemCount: widget.urls.length,
        onPageChanged: _changePage,
        itemBuilder: (ctx, i) {
          final overlay = widget.overlayBuilder?.call(
            ctx,
            i,
            FeedOverlayState(
              buffering: _buffering && i == _current,
              error: _error,
              positionMs: _posMs,
              durationMs: _durMs,
              scrollSpeedPxPerMs: _speedPxPerMs,
              direction: ((_pageCtrl.page ?? _current.toDouble()) - _current)
                  .sign
                  .toInt(),
              thumbnailLoading: _thumbLoading.contains(i),
              hasThumbnail: _thumbs.containsKey(i),
            ),
          );
          return _FeedItem(
            index: i,
            controller: _ctrl,
            buffering: _buffering && i == _current,
            thumbnail: _thumbs[i],
            overlay: overlay,
            adaptiveFit: widget.adaptiveFit,
            nearSquare: widget.nearSquare,
          );
        },
      ),
    );
  }
}

class _FeedItem extends StatefulWidget {
  final int index;
  final ShortsController controller;
  final bool buffering;
  final ImageProvider? thumbnail;
  final Widget? overlay;
  final BoxFit? fit;
  final bool adaptiveFit;
  final double nearSquare;

  const _FeedItem({
    required this.index,
    required this.controller,
    required this.buffering,
    required this.thumbnail,
    required this.overlay,
    this.fit,
    required this.adaptiveFit,
    required this.nearSquare,
  });

  @override
  State<_FeedItem> createState() => _FeedItemState();
}

class _FeedItemState extends State<_FeedItem>
    with SingleTickerProviderStateMixin {
  late final AnimationController _fade = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 250),
    value: 1,
  );

  @override
  void didUpdateWidget(covariant _FeedItem old) {
    super.didUpdateWidget(old);
    if (widget.buffering) {
      _fade.forward();
    } else {
      _fade.reverse();
    }
  }

  @override
  void dispose() {
    _fade.dispose();
    super.dispose();
  }

  BoxFit _calcFitFor(int index) {
    if (widget.fit != null) return widget.fit!;
    if (!widget.adaptiveFit) return BoxFit.contain;

    final meta = widget.controller.getMeta(index);
    if (meta == null || meta.width <= 0 || meta.height <= 0) {
      return BoxFit.cover;
    }
    final w = meta.width, h = meta.height;
    final ratio = h / w;
    if (ratio >= 1 && ratio <= widget.nearSquare) return BoxFit.cover;
    if (ratio < 1 && (1 / ratio) <= widget.nearSquare) return BoxFit.contain;
    return h > w ? BoxFit.cover : BoxFit.contain;
  }

  @override
  Widget build(BuildContext context) {
    final meta = widget.controller.getMeta(widget.index);
    final fit = _calcFitFor(widget.index);

    Widget videoWithFit = Center(
      child: FittedBox(
        fit: fit,
        alignment: Alignment.center,
        child: SizedBox(
          width: (meta?.width ?? MediaQuery.of(context).size.width).toDouble(),
          height: (meta?.height ?? (MediaQuery.of(context).size.width * 16 / 9))
              .toDouble(),
          child: ShortsView(index: widget.index),
        ),
      ),
    );

    return Stack(
      fit: StackFit.expand,
      children: [
        videoWithFit,
        if (widget.thumbnail != null)
          FadeTransition(
            opacity: _fade,
            child: DecoratedBox(
              decoration: const BoxDecoration(color: Colors.black),
              child: FittedBox(
                fit: BoxFit.cover,
                child: Image(image: widget.thumbnail!),
              ),
            ),
          ),
        if (widget.overlay != null) widget.overlay!,
      ],
    );
  }
}
