import 'dart:async';
import 'dart:collection';
import 'dart:math';
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
  final bool hasThumbnail;
  final bool firstFrameRendered;
  final int startupMs;
  final int firstFrameMs;
  final int rebufferCount;
  final int rebufferDurationMs;
  final int lastRebufferDurationMs;

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
    required this.hasThumbnail,
    required this.firstFrameRendered,
    required this.startupMs,
    required this.firstFrameMs,
    required this.rebufferCount,
    required this.rebufferDurationMs,
    required this.lastRebufferDurationMs,
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
    bool? hasThumbnail,
    bool? firstFrameRendered,
    int? startupMs,
    int? firstFrameMs,
    int? rebufferCount,
    int? rebufferDurationMs,
    int? lastRebufferDurationMs,
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
      hasThumbnail: hasThumbnail ?? this.hasThumbnail,
      firstFrameRendered: firstFrameRendered ?? this.firstFrameRendered,
      startupMs: startupMs ?? this.startupMs,
      firstFrameMs: firstFrameMs ?? this.firstFrameMs,
      rebufferCount: rebufferCount ?? this.rebufferCount,
      rebufferDurationMs: rebufferDurationMs ?? this.rebufferDurationMs,
      lastRebufferDurationMs: lastRebufferDurationMs ?? this.lastRebufferDurationMs,
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

  const ShortsFeed({
    super.key,
    required this.urls,
    this.controller,
    this.preloadWindow = const PreloadWindow(),
    this.scrollPrewarm = const ScrollPrewarm(),
    this.qualityPreset = ShortsQuality.auto,
    this.showThumbnailsWhileBuffering = true,
    this.overlayBuilder,
    this.coverBuilder,
    this.onPageChanged,
    this.fit,
    this.adaptiveFit = true,
    this.nearSquare = 1.1,
    this.adaptivePreload,
    this.thumbnailCacheCapacity = 12,
    this.maxActivePlayers,
    this.prefetchBytesLimit,
    this.forwardBufferDuration,
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
  final Set<int> _thumbLoading = {};
  final Set<int> _firstFrameSeen = {};
  final Map<int, int> _startupMs = {};
  final Map<int, int> _firstFrameMs = {};
  final Map<int, int> _rebufferCount = {};
  final Map<int, int> _rebufferDurationMs = {};
  final Map<int, int> _lastRebufferDurationMs = {};
  DateTime? _lastSmoothPlayback;
  DateTime? _lastRebuffer;

  bool _isValidIndex(int index) =>
      index >= 0 && index < widget.urls.length;

  Future<void> _primeIndex(int index) async {
    if (!_isValidIndex(index)) return;
    await _ctrl.prewarmAround(index, forward: 0, backward: 0);
  }

  void _schedulePrefetch(int index) {
    if (!_isValidIndex(index)) return;

    void queue(int idx) {
      if (_isValidIndex(idx)) {
        unawaited(_ctrl.ensureMetadata(idx));
      }
    }

    queue(index);
    queue(index + 1);
    queue(index - 1);

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
    // прогрев окна вокруг 0
    final initialFwd = _effectiveForward();
    final initialBack = _effectiveBackward();
    await _ctrl.prewarmAround(0, forward: initialFwd, backward: initialBack);
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
    } else if (e is FirstFrameEvent) {
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
    if (!_isValidIndex(target)) return;

    // прогреваем целевую
    await _primeIndex(target);
    _schedulePrefetch(target);

    final forwardRange = dir > 0
        ? _effectiveForward(fastSwipe: fast)
        : _effectiveBackward();
    for (int k = 1; k <= forwardRange; k++) {
      final idx = target + dir * k;
      if (!_isValidIndex(idx)) continue;
      await _primeIndex(idx);
      _schedulePrefetch(idx);
    }

    final oppositeRange = dir > 0
        ? _effectiveBackward()
        : _effectiveForward(fastSwipe: false);
    for (int k = 1; k <= oppositeRange; k++) {
      final idx = _current + (dir > 0 ? -k : k);
      if (!_isValidIndex(idx)) continue;

      await _primeIndex(idx);
      _schedulePrefetch(idx);
    }
  }

  Future<void> _changePage(int i) async {
    await _ctrl.onInactive(_current);
    _current = i;
    await _ctrl.onActive(i);
    await _ctrl.prewarmAround(i,
        forward: _effectiveForward(), backward: _effectiveBackward());

    _schedulePrefetch(i);
    widget.onPageChanged?.call(i);
    setState(() {
      _buffering = false;
      _error = null;
      _posMs = 0;
      _bufMs = 0;
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
        _storeThumbnail(index, MemoryImage(bytes));
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
              bufferedMs: _bufMs,
              scrollSpeedPxPerMs: _speedPxPerMs,
              direction: ((_pageCtrl.page ?? _current.toDouble()) - _current)
                  .sign
                  .toInt(),
              thumbnailLoading: _thumbLoading.contains(i),
              hasThumbnail: _thumbs.containsKey(i),
              firstFrameRendered: _firstFrameSeen.contains(i),
              startupMs: _startupMs[i] ?? -1,
              firstFrameMs: _firstFrameMs[i] ?? -1,
              rebufferCount: _rebufferCount[i] ?? 0,
              rebufferDurationMs: _rebufferDurationMs[i] ?? 0,
              lastRebufferDurationMs: _lastRebufferDurationMs[i] ?? 0,
            ),
          );
          return _FeedItem(
            index: i,
            controller: _ctrl,
            buffering: _buffering && i == _current,
            thumbnail: _thumbs[i],
            overlay: overlay,
            fit: widget.fit,
            showCover: !_firstFrameSeen.contains(i),
            adaptiveFit: widget.adaptiveFit,
            nearSquare: widget.nearSquare,
            coverBuilder: widget.coverBuilder,
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
    );
  }
}

class _DefaultCover extends StatelessWidget {
  final ImageProvider? thumbnail;
  final bool buffering;

  const _DefaultCover({this.thumbnail, required this.buffering});

  @override
  Widget build(BuildContext context) {
    final hasThumbnail = thumbnail != null;
    return DecoratedBox(
      decoration: const BoxDecoration(color: Colors.black),
      child: Stack(
        fit: StackFit.expand,
        children: [
          if (hasThumbnail)
            FittedBox(
              fit: BoxFit.cover,
              child: Image(image: thumbnail!),
            ),
          if (hasThumbnail)
            DecoratedBox(
              decoration: BoxDecoration(
                color: Colors.black.withOpacity(0.35),
              ),
            ),
          if (buffering)
            const Center(
              child: SizedBox(
                width: 32,
                height: 32,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
        ],
      ),
    );
  }
}
