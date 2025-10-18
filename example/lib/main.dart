import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:shorts_hls_player/shorts_hls_player.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const DemoApp());
}

class DemoApp extends StatelessWidget {
  const DemoApp({super.key});
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Shorts HLS Demo',
      theme: ThemeData.dark(),
      home: const DemoHome(),
    );
  }
}

class DemoHome extends StatefulWidget {
  const DemoHome({super.key});
  @override
  State<DemoHome> createState() => _DemoHomeState();
}

class _DemoHomeState extends State<DemoHome> {
  final _ctrl = ShortsController();
  List<Uri> _seed = [];          // исходный набор ссылок
  List<Uri> _urls = [];          // текущий увеличиваемый список
  String? _error;
  bool _loading = true;
  bool _loadingMore = false;      // флаг, чтобы не триггерить догрузку повторно

  @override
  void initState() {
    super.initState();
    _loadSeed();
  }

  Future<void> _loadSeed() async {
    try {
      final raw = await rootBundle.loadString('assets/demo_urls.txt');
      final list = raw
          .split('\n')
          .map((s) => s.trim())
          .where((s) => s.isNotEmpty)
          .map(Uri.parse)
          .toList();

      if (list.isEmpty) {
        setState(() {
          _error = 'В assets/demo_urls.txt нет ссылок .m3u8';
          _loading = false;
        });
        return;
      }

      _seed = List<Uri>.from(list);
      _urls = List<Uri>.from(list); // стартуем с исходного набора
      await _ctrl.setLooping(true);
      await _ctrl.setProgressTracking(enabled: true);

      setState(() {
        _error = null;
        _loading = false;
      });
    } catch (e) {
      setState(() {
        _error = 'Не удалось прочитать assets/demo_urls.txt: $e';
        _loading = false;
      });
    }
  }

  /// Эмуляция пагинации:
  /// как только текущая страница >= половины текущего размера — добавляем
  /// ещё столько, сколько было в исходном наборе (_seed.length).
  Future<void> _maybePaginate(int currentIndex) async {
    if (_loadingMore || _seed.isEmpty) return;
    final threshold = _urls.length ~/ 2;
    if (currentIndex < threshold) return;

    _loadingMore = true;

    // соберём следующую «страницу» той же длины, циклически по seed
    final next = <Uri>[];
    for (int i = 0; i < _seed.length; i++) {
      final uri = _seed[i % _seed.length];
      next.add(uri);
    }

    // отдадим новые URL в контроллер и обновим список для виджета
    await _ctrl.append(next);
    setState(() {
      _urls.addAll(next);
    });

    _loadingMore = false;
  }

  Future<void> _showQualitySheet() async {
    if (!mounted) return;
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.black87,
      builder: (_) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            _presetTile('Auto', ShortsQuality.auto),
            _presetTile('360p', ShortsQuality.p360),
            _presetTile('480p', ShortsQuality.p480),
            _presetTile('720p', ShortsQuality.p720),
            _presetTile('1080p', ShortsQuality.p1080),
            _presetTile('Best', ShortsQuality.best),
          ],
        ),
      ),
    );
  }

  ListTile _presetTile(String title, ShortsQuality q) => ListTile(
    title: Text(title),
    onTap: () async {
      await _ctrl.setQualityPreset(q);
      if (mounted) Navigator.pop(context);
    },
  );

  @override
  Widget build(BuildContext context) {
    final body = _loading
        ? const Center(child: CircularProgressIndicator())
        : (_error != null
        ? Center(child: Text(_error!, textAlign: TextAlign.center))
        : ShortsFeed(
      urls: _urls,
      controller: _ctrl,
      preloadWindow: const PreloadWindow(fwd: 2, back: 1),
      scrollPrewarm: const ScrollPrewarm(
        triggerDelta: 0.25,
        fastSwipeSpeed: 0.8,
        extraOnFast: 2,
      ),
      qualityPreset: ShortsQuality.auto,
      showThumbnailsWhileBuffering: true,
      overlayBuilder: (ctx, index, st) {

        if (!st.hasThumbnail && st.positionMs == 0 && st.durationMs == 0) {
          return Center(
            child: Container(
              color: Colors.black,
              child: const Text(
                "Видео недоступно",
                style: TextStyle(color: Colors.white),
              ),
            ),
          );
        }

        return Align(
          alignment: Alignment.bottomCenter,
          child: Container(
            margin: const EdgeInsets.all(12),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: Colors.black54,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(
              children: [
                if (st.error != null) ...[
                  const Icon(Icons.error, size: 16, color: Colors.redAccent),
                  const SizedBox(width: 6),
                  Flexible(
                    child: Text(
                      st.error!,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.redAccent),
                    ),
                  ),
                ] else ...[
                  if (st.buffering)
                    const SizedBox(
                      width: 16, height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  const SizedBox(width: 6),
                  Text('${st.positionLabel} / ${st.durationLabel}'),
                  const SizedBox(width: 12),
                  if (st.thumbnailLoading && !st.hasThumbnail) ...[
                    const Icon(Icons.image_search, size: 16, color: Colors.white70),
                    const SizedBox(width: 4),
                    const Text('loading preview…',
                        style: TextStyle(fontSize: 12, color: Colors.white70)),
                  ],
                ],
                const Spacer(),
                IconButton(
                  icon: const Icon(Icons.hd),
                  tooltip: 'Quality',
                  onPressed: _showQualitySheet,
                ),
              ],
            ),
          ),
        );
      },
      onPageChanged: (i) {
        _maybePaginate(i);
      },
    ));

    return Scaffold(
      appBar: AppBar(
        title: const Text('Shorts HLS Demo • Pagination'),
        actions: [
          IconButton(
            icon: const Icon(Icons.hd),
            onPressed: _showQualitySheet,
          ),
        ],
      ),
      body: body,
    );
  }
}
