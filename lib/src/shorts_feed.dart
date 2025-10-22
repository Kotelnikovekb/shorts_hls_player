import 'dart:async';
import 'dart:collection';
import 'dart:math';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import '../shorts_hls_player.dart';

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

class AdaptivePreloadConfig {
  final int minForward;
  final int minBackward;
  final int maxForward;
  final int maxBackward;
  final Duration expandAfter;
  final Duration rebufferCooldown;

  const AdaptivePreloadConfig({
    this.minForward = 1,
    this.minBackward = 0,
    this.maxForward = 3,
    this.maxBackward = 1,
    this.expandAfter = const Duration(seconds: 8),
    this.rebufferCooldown = const Duration(seconds: 5),
  });
}

class FeedOverlayState {
  final bool buffering;
  final String? error;
  final int positionMs;
  final int durationMs;
  final int bufferedMs;
  final double scrollSpeedPxPerMs;
  final int direction; // -1 вверх, +1 вниз, 0
  final bool thumbnailLoading;
  final bool thumbnailError;
  final bool hasThumbnail;
  final bool firstFrameRendered;
  final int startupMs;
  final int firstFrameMs;
  final int rebufferCount;
  final int rebufferDurationMs;
  final int lastRebufferDurationMs;
  final bool showingPreview;
  final int previewRemainingMs;

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
    required this.bufferedMs,
    required this.scrollSpeedPxPerMs,
    required this.direction,
    required this.thumbnailLoading,
    required this.thumbnailError,
    required this.hasThumbnail,
    required this.firstFrameRendered,
    required this.startupMs,
    required this.firstFrameMs,
    required this.rebufferCount,
    required this.rebufferDurationMs,
    required this.lastRebufferDurationMs,
    required this.showingPreview,
    required this.previewRemainingMs,
  });


  FeedOverlayState copyWith({
    bool? buffering,
    String? error,
    int? positionMs,
    int? durationMs,
    int? bufferedMs,
    double? scrollSpeedPxPerMs,
    int? direction,
    bool? thumbnailLoading,
    bool? thumbnailError,
    bool? hasThumbnail,
    bool? firstFrameRendered,
    int? startupMs,
    int? firstFrameMs,
    int? rebufferCount,
    int? rebufferDurationMs,
    int? lastRebufferDurationMs,
    bool? showingPreview,
    int? previewRemainingMs,
  }) {
    return FeedOverlayState(
      buffering: buffering ?? this.buffering,
      error: error ?? this.error,
      positionMs: positionMs ?? this.positionMs,
      durationMs: durationMs ?? this.durationMs,
      bufferedMs: bufferedMs ?? this.bufferedMs,
      scrollSpeedPxPerMs: scrollSpeedPxPerMs ?? this.scrollSpeedPxPerMs,
      direction: direction ?? this.direction,
      thumbnailLoading: thumbnailLoading ?? this.thumbnailLoading,
      thumbnailError: thumbnailError ?? this.thumbnailError,
      hasThumbnail: hasThumbnail ?? this.hasThumbnail,
      firstFrameRendered: firstFrameRendered ?? this.firstFrameRendered,
      startupMs: startupMs ?? this.startupMs,
      firstFrameMs: firstFrameMs ?? this.firstFrameMs,
      rebufferCount: rebufferCount ?? this.rebufferCount,
      rebufferDurationMs: rebufferDurationMs ?? this.rebufferDurationMs,
      lastRebufferDurationMs: lastRebufferDurationMs ?? this.lastRebufferDurationMs,
      showingPreview: showingPreview ?? this.showingPreview,
      previewRemainingMs: previewRemainingMs ?? this.previewRemainingMs,
    );
  }
}

typedef FeedOverlayBuilder = Widget Function(
  BuildContext context,
  int index,
  FeedOverlayState state,
);

typedef CoverBuilder = Widget Function(
  BuildContext context,
  int index,
  ImageProvider? thumbnail,
  bool isBuffering,
  bool shouldShowCover,
);

class ShortsFeed extends StatefulWidget {
  final List<Uri> urls;
  final ShortsController? controller;
  final PreloadWindow preloadWindow;
  final ScrollPrewarm scrollPrewarm;
  final ShortsQuality qualityPreset;
  final bool showThumbnailsWhileBuffering;
  final FeedOverlayBuilder? overlayBuilder;
  final CoverBuilder? coverBuilder;
  final ValueChanged<int>? onPageChanged;
  final BoxFit? fit;
  final bool adaptiveFit;
  final double nearSquare;
  final AdaptivePreloadConfig? adaptivePreload;
  final int thumbnailCacheCapacity;
  final int? maxActivePlayers;
  final int? prefetchBytesLimit;
  final Duration? forwardBufferDuration;
  final ImageProvider? Function(int index)? previewImageBuilder;

  const ShortsFeed({
    super.key,
    required this.urls,
    this.controller,
    this.preloadWindow = const PreloadWindow(fwd: 2, back: 1),
    this.scrollPrewarm = const ScrollPrewarm(triggerDelta: 0.1, fastSwipeSpeed: 2.0, extraOnFast: 1),
    this.qualityPreset = ShortsQuality.auto,
    this.showThumbnailsWhileBuffering = true,
    this.overlayBuilder,
    this.coverBuilder,
    this.onPageChanged,
    this.fit,
    this.adaptiveFit = true,
    this.nearSquare = 1.1,
    this.adaptivePreload,
    this.thumbnailCacheCapacity = 20,
    this.maxActivePlayers = 5,
    this.prefetchBytesLimit = 8 * 1024 * 1024, // 8MB
    this.forwardBufferDuration = const Duration(seconds: 3),
    this.previewImageBuilder,
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
  int _posMs = 0, _durMs = 0, _bufMs = 0;

  // скролл-метрики
  double _lastPixels = 0;
  DateTime? _lastTs;
  double _speedPxPerMs = 0.0;
  double _lastBucket = -999;

  // превью-кэш
  final LinkedHashMap<int, ImageProvider> _thumbs = LinkedHashMap();
  final Set<int> _thumbErrors = {};
  final Set<int> _firstFrameSeen = {};
  final Map<int, int> _startupMs = {};
  final Map<int, int> _firstFrameMs = {};
  final Map<int, int> _rebufferCount = {};
  final Map<int, int> _rebufferDurationMs = {};
  final Map<int, int> _lastRebufferDurationMs = {};
  final Set<int> _priming = {};
  DateTime? _lastSmoothPlayback;
  DateTime? _lastRebuffer;
  
  bool _isValidIndex(int index) =>
      index >= 0 && index < widget.urls.length;

  void _primeIndex(int index) {
    if (!_isValidIndex(index)) return;
    if (!_priming.add(index)) return;
    unawaited(_ctrl
        .prewarmAround(index, forward: 0, backward: 0)
        .whenComplete(() => _priming.remove(index)));
  }

  void _schedulePrefetch(int index) {
    if (!_isValidIndex(index)) return;

    // Параллельная загрузка для мгновенного отклика
    final futures = <Future>[];
    
    void queue(int idx) {
      if (_isValidIndex(idx)) {
        futures.add(_ctrl.ensureMetadata(idx));
      }
    }

    queue(index);
    queue(index + 1);
    queue(index - 1);
    
    // Параллельно загружаем метаданные и превью
    unawaited(Future.wait(futures));
    _ensureThumb(index);
  }

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
      _ctrl.dispose();
    }
    super.dispose();
  }

  @override
  void didUpdateWidget(covariant ShortsFeed oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.thumbnailCacheCapacity < oldWidget.thumbnailCacheCapacity) {
      _trimThumbnailCache();
    }
  }

  Future<void> _bootstrap() async {
    _ctrl.maxActivePlayers ??= widget.maxActivePlayers;
    _ctrl.prefetchBytesLimit ??= widget.prefetchBytesLimit;
    _ctrl.forwardBufferDuration ??= widget.forwardBufferDuration;
    await _ctrl.init();
    await _ctrl.setQualityPreset(widget.qualityPreset);
    _sub = _ctrl.events.listen(_onEvent);

    await _ctrl.append(widget.urls);

    // Подготовим превью-кадры как можно раньше, чтобы не мигал чёрный экран
    for (int i = 0; i <= min(3, widget.urls.length - 1); i++) {
      _ensureThumb(i);
    }

    // Агрессивный прогрев для мгновенного старта
    final initialFwd = _effectiveForward();
    final initialBack = _effectiveBackward();
    await _ctrl.prewarmAround(0, forward: initialFwd, backward: initialBack);
    
    // Мгновенная загрузка метаданных и превью для первых элементов
    final futures = <Future>[];
    for (int i = 0; i <= min(2, widget.urls.length - 1); i++) {
      futures.add(_ctrl.ensureMetadata(i));
    }
    await Future.wait(futures);
    
    // Сразу начинаем загрузку первого видео
    await _ctrl.onActive(0, autoPlay: true);
    
    setState(() {});
  }

  void _onEvent(ShortsEvent e) {
    if (e is ReadyEvent) {
      _completePreview(e.index, triggerPlayback: true);
    }
    if (e is OnBufferingStart) {
      if (e.index == _current) setState(() => _buffering = true);
      _recordRebuffer();
    } else if (e is OnBufferingEnd) {
      if (e.index == _current) setState(() => _buffering = false);
    } else if (e is OnError) {
      if (e.index == _current) setState(() => _error = e.message);
    } else if (e is OnProgress && e.index == _current) {
      setState(() {
        _posMs = e.posMs;
        _durMs = e.durMs ?? _durMs;
        _bufMs = e.bufferedMs ?? _bufMs;
        if (e.posMs > 0) {
          _firstFrameSeen.add(e.index);
          _recordSmoothPlayback();
        }
      });
    } else if (e is ProgressEvent && e.index == _current) {
      setState(() {
        _posMs = e.position.inMilliseconds;
        final dur = e.duration.inMilliseconds;
        if (dur > 0) {
          _durMs = dur;
        }
        _bufMs = e.bufferedMs ?? _bufMs;
        if (e.position > Duration.zero) {
          _firstFrameSeen.add(e.index);
          _recordSmoothPlayback();
        }
      });
    } else if (e is MetricsEvent) {
      final idx = e.index;
      if (e.startupMs != null) _startupMs[idx] = e.startupMs!;
      if (e.firstFrameMs != null) _firstFrameMs[idx] = e.firstFrameMs!;
      _rebufferCount[idx] = e.rebufferCount;
      _rebufferDurationMs[idx] = e.rebufferDurationMs;
      if (e.lastRebufferDurationMs != null) {
        _lastRebufferDurationMs[idx] = e.lastRebufferDurationMs!;
      }
      if ((e.firstFrameMs ?? -1) >= 0) {
        _firstFrameSeen.add(idx);
      }
      if (e.rebufferCount > 0 || e.rebufferDurationMs > 0) {
        _recordRebuffer();
      } else {
        _recordSmoothPlayback();
      }
      if (mounted) {
        setState(() {});
      }
      if (kDebugMode) {
        debugPrint(
            'ShortsFeed: metrics index=$idx startup=${e.startupMs ?? -1}ms '
            'firstFrame=${e.firstFrameMs ?? -1}ms '
            'rebufferCount=${e.rebufferCount} '
            'rebufferDuration=${e.rebufferDurationMs}ms');
      }
    } else if (e is FirstFrameEvent) {
      _completePreview(e.index, triggerPlayback: true);
      bool shouldUpdate = false;
      if (_firstFrameSeen.add(e.index)) {
        shouldUpdate = true;
      }
      if (e.index == _current && _buffering) {
        _buffering = false;
        shouldUpdate = true;
      }
      _recordSmoothPlayback();
      if (shouldUpdate && mounted) {
        setState(() {});
      }
      if (kDebugMode) {
        debugPrint('ShortsFeed: first frame rendered index=${e.index}');
      }
      if (_thumbErrors.contains(e.index)) {
        _ensureThumb(e.index);
      }
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
        dir, target, (_speedPxPerMs >= widget.scrollPrewarm.fastSwipeSpeed));
  }

  void _prewarmDirection(int dir, int target, bool fast) {
    if (!_isValidIndex(target)) return;

    // Мгновенный прогрев для TikTok-подобной скорости
    _primeIndex(target);
    _schedulePrefetch(target);

    final forwardRange = dir > 0
        ? _effectiveForward(fastSwipe: fast)
        : _effectiveBackward();
    for (int k = 1; k <= forwardRange; k++) {
      final idx = target + dir * k;
      if (!_isValidIndex(idx)) continue;
      _primeIndex(idx);
      _schedulePrefetch(idx);
    }

    final oppositeRange = dir > 0
        ? _effectiveBackward()
        : _effectiveForward(fastSwipe: false);
    for (int k = 1; k <= oppositeRange; k++) {
      final idx = _current + (dir > 0 ? -k : k);
      if (!_isValidIndex(idx)) continue;
      _primeIndex(idx);
      _schedulePrefetch(idx);
    }
  }

  Future<void> _changePage(int i) async {
    // Мгновенное переключение без ожидания
    _ctrl.onInactive(_current);
    _current = i;
    
    // Параллельное выполнение для максимальной скорости
    final futures = <Future>[
      _ctrl.onActive(i, autoPlay: true),
      _ctrl.prewarmAround(i, forward: _effectiveForward(), backward: _effectiveBackward()),
    ];
    _schedulePrefetch(i);
    
    widget.onPageChanged?.call(i);
    setState(() {
      _buffering = false;
      _error = null;
      _posMs = 0;
      _bufMs = 0;
    });
    // Не ждем завершения - переключение мгновенное
    unawaited(Future.wait(futures));
  }

  Future<void> _ensureThumb(int index) async {
    if (!widget.showThumbnailsWhileBuffering) return;
    final external = _externalPreview(index);
    if (external != null) {
      _storeThumbnail(index, external);
      _thumbErrors.remove(index);
      if (mounted) setState(() {});
      return;
    }
    if (_thumbs.containsKey(index)) return;
    _thumbErrors.remove(index);
    if (mounted) setState(() {});
  }

  void _retryThumbnail(int index) {
    if (!_isValidIndex(index)) return;
    _thumbErrors.remove(index);
    _thumbs.remove(index);
    _ensureThumb(index);
    if (mounted) setState(() {});
  }

  void _completePreview(int index, {bool triggerPlayback = false}) {
    if (triggerPlayback && mounted && _current == index) {
      unawaited(_ctrl.play(index));
    }
  }

  ImageProvider? _externalPreview(int index) {
    final builder = widget.previewImageBuilder;
    if (builder == null) return null;
    try {
      final provider = builder(index);
      return provider;
    } catch (err) {
      if (kDebugMode) {
        debugPrint('ShortsFeed: external preview builder failed for index $index – $err');
      }
      return null;
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
          const isShowingPreview = false;
          const previewRemainingMs = 0;
          const isThumbLoading = false;
          final hasThumbError = _thumbErrors.contains(i);
          
          final overlay = widget.overlayBuilder?.call(
            ctx,
            i,
            FeedOverlayState(
              buffering: _buffering && i == _current,
              error: _error,
              positionMs: _posMs,
              durationMs: _durMs,
              bufferedMs: _bufMs,
              scrollSpeedPxPerMs: _speedPxPerMs,
              direction: ((_pageCtrl.page ?? _current.toDouble()) - _current)
                  .sign
                  .toInt(),
              thumbnailLoading: isThumbLoading,
              hasThumbnail: _thumbs.containsKey(i),
              firstFrameRendered: _firstFrameSeen.contains(i),
              startupMs: _startupMs[i] ?? -1,
              firstFrameMs: _firstFrameMs[i] ?? -1,
              rebufferCount: _rebufferCount[i] ?? 0,
              rebufferDurationMs: _rebufferDurationMs[i] ?? 0,
              lastRebufferDurationMs: _lastRebufferDurationMs[i] ?? 0,
              showingPreview: isShowingPreview,
              previewRemainingMs: previewRemainingMs,
              thumbnailError: hasThumbError,
            ),
          );
          return _FeedItem(
            index: i,
            controller: _ctrl,
            buffering: _buffering && i == _current,
            thumbnail: _thumbs[i],
            overlay: overlay,
            fit: widget.fit,
            showCover: !_firstFrameSeen.contains(i) || isShowingPreview,
            adaptiveFit: widget.adaptiveFit,
            nearSquare: widget.nearSquare,
            coverBuilder: widget.coverBuilder,
            showingPreview: isShowingPreview,
            previewRemainingMs: previewRemainingMs,
            thumbnailLoading: isThumbLoading,
            thumbnailError: hasThumbError,
            onRetryThumbnail: () => _retryThumbnail(i),
          );
        },
      ),
    );
  }

  AdaptivePreloadConfig? get _adaptive => widget.adaptivePreload;

  int _effectiveForward({bool fastSwipe = false}) {
    final cfg = _adaptive;
    int target = widget.preloadWindow.fwd;
    if (cfg == null) {
      if (fastSwipe) {
        target = max(0, target + widget.scrollPrewarm.extraOnFast);
      }
      return max(0, target);
    }
    final now = DateTime.now();
    final minFwd = cfg.minForward;
    final maxFwd = max(cfg.maxForward, minFwd);
    target = _clampWindow(target, minFwd, maxFwd);
    if (_lastRebuffer != null &&
        now.difference(_lastRebuffer!) < cfg.rebufferCooldown) {
      return minFwd;
    }
    if (fastSwipe) {
      target = max(target + widget.scrollPrewarm.extraOnFast, minFwd);
    }
    if (_lastSmoothPlayback != null &&
        now.difference(_lastSmoothPlayback!) >= cfg.expandAfter) {
      target = maxFwd;
    }
    return _clampWindow(target, minFwd, maxFwd);
  }

  int _effectiveBackward() {
    final cfg = _adaptive;
    int target = widget.preloadWindow.back;
    if (cfg == null) {
      return max(0, target);
    }
    final now = DateTime.now();
    final minBack = cfg.minBackward;
    final maxBack = max(cfg.maxBackward, minBack);
    target = _clampWindow(target, minBack, maxBack);
    if (_lastRebuffer != null &&
        now.difference(_lastRebuffer!) < cfg.rebufferCooldown) {
      return minBack;
    }
    if (_lastSmoothPlayback != null &&
        now.difference(_lastSmoothPlayback!) >= cfg.expandAfter) {
      target = maxBack;
    }
    return _clampWindow(target, minBack, maxBack);
  }

  int _clampWindow(int value, int minValue, int maxValue) {
    final lower = minValue;
    final upper = max(maxValue, lower);
    if (value < lower) return lower;
    if (value > upper) return upper;
    return value;
  }

  void _recordSmoothPlayback() {
    _lastSmoothPlayback = DateTime.now();
  }

  void _recordRebuffer() {
    _lastRebuffer = DateTime.now();
  }

  void _storeThumbnail(int index, ImageProvider provider) {
    _thumbs.remove(index);
    _thumbs[index] = provider;
    _trimThumbnailCache();
    if (mounted) {
      unawaited(precacheImage(provider, context).catchError((_) {}));
    }
  }

  void _trimThumbnailCache() {
    final targetSize = max(0, widget.thumbnailCacheCapacity);
    while (_thumbs.length > targetSize && _thumbs.isNotEmpty) {
      final oldestKey = _thumbs.keys.first;
      _thumbs.remove(oldestKey);
    }
  }
}

class _FeedItem extends StatefulWidget {
  final int index;
  final ShortsController controller;
  final bool buffering;
  final bool showCover;
  final ImageProvider? thumbnail;
  final Widget? overlay;
  final BoxFit? fit;
  final bool adaptiveFit;
  final double nearSquare;
  final CoverBuilder? coverBuilder;
  final bool showingPreview;
  final int previewRemainingMs;
  final bool thumbnailLoading;
  final bool thumbnailError;
  final VoidCallback onRetryThumbnail;

  const _FeedItem({
    required this.index,
    required this.controller,
    required this.buffering,
    required this.showCover,
    required this.thumbnail,
    required this.overlay,
    this.fit,
    required this.adaptiveFit,
    required this.nearSquare,
    required this.coverBuilder,
    required this.showingPreview,
    required this.previewRemainingMs,
    required this.thumbnailLoading,
    required this.thumbnailError,
    required this.onRetryThumbnail,
  });

  @override
  State<_FeedItem> createState() => _FeedItemState();
}

class _FeedItemState extends State<_FeedItem>
    with SingleTickerProviderStateMixin {
  late final AnimationController _fade;

  bool get _shouldShowOverlay => widget.showCover || widget.buffering;

  @override
  void initState() {
    super.initState();
    _fade = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 250),
      value: _shouldShowOverlay ? 1 : 0,
    );
  }

  @override
  void didUpdateWidget(covariant _FeedItem old) {
    super.didUpdateWidget(old);
    final shouldShow = _shouldShowOverlay;
    final prevShouldShow = old.showCover || old.buffering;

    if (shouldShow && !prevShouldShow) {
      _fade.forward();
    } else if (!shouldShow && prevShouldShow) {
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
        FadeTransition(
          opacity: _fade,
          child: _buildCover(context),
        ),
        if (widget.overlay != null) widget.overlay!,
      ],
    );
  }

  Widget _buildCover(BuildContext context) {
    final builder = widget.coverBuilder;
    if (builder != null) {
      return builder(
        context,
        widget.index,
        widget.thumbnail,
        widget.buffering,
        widget.showCover,
      );
    }
    return _DefaultCover(
      thumbnail: widget.thumbnail,
      buffering: widget.buffering,
      showingPreview: widget.showingPreview,
      previewRemainingMs: widget.previewRemainingMs,
      thumbnailLoading: widget.thumbnailLoading,
      thumbnailError: widget.thumbnailError,
      onRetry: widget.onRetryThumbnail,
    );
  }
}

class _DefaultCover extends StatelessWidget {
  final ImageProvider? thumbnail;
  final bool buffering;
  final bool showingPreview;
  final int previewRemainingMs;
  final bool thumbnailLoading;
  final bool thumbnailError;
  final VoidCallback onRetry;

  const _DefaultCover({
    this.thumbnail, 
    required this.buffering,
    required this.showingPreview,
    required this.previewRemainingMs,
    required this.thumbnailLoading,
    required this.thumbnailError,
    required this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    final countdownSeconds =
        previewRemainingMs > 0 ? (previewRemainingMs / 1000).ceil() : 0;

    final children = <Widget>[
      Positioned.fill(child: _buildBaseLayer(context)),
    ];

    if (!thumbnailError && (thumbnailLoading || buffering)) {
      children.add(_buildLoadingOverlay());
    }

    if (!thumbnailError &&
        showingPreview &&
        thumbnail != null &&
        countdownSeconds > 0) {
      children.add(_buildPreviewOverlay(countdownSeconds));
    }

    return Stack(
      fit: StackFit.expand,
      children: children,
    );
  }

  Widget _buildBaseLayer(BuildContext context) {
    if (thumbnail != null) {
      return DecoratedBox(
        decoration: BoxDecoration(
          image: DecorationImage(
            image: thumbnail!,
            fit: BoxFit.cover,
          ),
        ),
      );
    }
    if (thumbnailError) {
      return _buildErrorFallback(context);
    }
    return const ColoredBox(color: Colors.black);
  }

  Widget _buildLoadingOverlay() {
    return Container(
      color: Colors.black.withValues(alpha: 0.25),
      child: const Center(
        child: SizedBox(
          width: 40,
          height: 40,
          child: CircularProgressIndicator(
            strokeWidth: 3,
            valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
            backgroundColor: Colors.white24,
          ),
        ),
      ),
    );
  }

  Widget _buildPreviewOverlay(int countdownSeconds) {
    return Container(
      color: Colors.black54,
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.play_circle_outline,
              size: 64,
              color: Colors.white,
            ),
            const SizedBox(height: 16),
            const Text(
              'Превью видео',
              style: TextStyle(
                color: Colors.white,
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            if (countdownSeconds > 0) ...[
              const SizedBox(height: 8),
              Text(
                'Старт через $countdownSeconds с',
                style: const TextStyle(
                  color: Colors.white70,
                  fontSize: 14,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildErrorFallback(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          colors: [
            Color(0xFF1F1F1F),
            Color(0xFF121212),
          ],
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
        ),
      ),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 260),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(
                Icons.image_not_supported_outlined,
                size: 56,
                color: Colors.white70,
              ),
              const SizedBox(height: 16),
              const Text(
                'Не удалось загрузить превью',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                'Проверьте подключение и попробуйте снова.',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.white70,
                  fontSize: 14,
                ),
              ),
              const SizedBox(height: 16),
              ElevatedButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh),
                label: const Text('Повторить'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
