import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:shorts_hls_player/src/types.dart';
import 'package:shorts_hls_player/zhabby_shorts.dart';

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
      home: const DemoFeed(),
    );
  }
}

class DemoFeed extends StatefulWidget {
  const DemoFeed({super.key});
  @override
  State<DemoFeed> createState() => _DemoFeedState();
}

class _DemoFeedState extends State<DemoFeed> {
  final _controller = ShortsController();
  final _events = <ShortsEvent>[];
  StreamSubscription<ShortsEvent>? _sub;

  List<Uri> _urls = [];
  int _current = 0;
  bool _buffering = false;
  String _err = '';
  int _posMs = 0, _durMs = 0;

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _bootstrap() async {
    // 1) читаем список ссылок из assets/demo_urls.txt
    final raw = await rootBundle.loadString('assets/demo_urls.txt');
    _urls = raw
        .split('\n')
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .map(Uri.parse)
        .toList();

    if (_urls.isEmpty) {
      setState(() => _err = 'В assets/demo_urls.txt нет ссылок .m3u8');
      return;
    }

    // 2) инициализируем плагин и подписываемся на события
    await _controller.init();
    _sub = _controller.events.listen((e) {
      _events.add(e);
      if (!mounted) return;
      if (e is OnBufferingStart) setState(() => _buffering = true);
      if (e is OnBufferingEnd) setState(() => _buffering = false);
      if (e is OnError) setState(() => _err = e.message);
      if (e is OnProgress && e.index == _current) {
        setState(() {
          _posMs = e.posMs;
          _durMs = e.durMs ?? _durMs;
        });
      }
    });

    // 3) передаём список URL в нативную часть и стартуем
    await _controller.append(_urls);
    _current = 0;
    await _controller.prewarmAround(_current, forward: 1, backward: 0);
    await _controller.onActive(_current);
    setState(() {
      _buffering = false;
      _err = '';
      _posMs = 0;
      _durMs = 0;
    });
  }

  Future<void> _changePage(int i) async {
    await _controller.onInactive(_current);
    _current = i;
    await _controller.onActive(i);
    await _controller.prewarmAround(i, forward: 1, backward: 1);
    if (!mounted) return;
    setState(() {
      _buffering = false;
      _err = '';
      _posMs = 0;
    });
  }

  Future<void> _showQualitySheet() async {
    final ctx = context;
    final variants = await _controller.getVariants(_current);
    if (!mounted) return;
    showModalBottomSheet(
      context: ctx,
      backgroundColor: Colors.black87,
      builder: (_) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            ListTile(
              title: const Text('Auto'),
              onTap: () async {
                await _controller.setQualityPreset(ShortsQuality.auto);
                if (mounted) Navigator.pop(ctx);
              },
            ),
            ..._presetTiles(ctx),
            if (variants.isNotEmpty) const Divider(),
            ...variants.map((v) => ListTile(
              title: Text(v.label),
              subtitle: (v.width != null && v.height != null)
                  ? Text('${v.width}x${v.height}  ${v.bitrate ?? ''}')
                  : null,
            )),
          ],
        ),
      ),
    );
  }

  List<Widget> _presetTiles(BuildContext ctx) => [
    _preset('360p', ShortsQuality.p360),
    _preset('480p', ShortsQuality.p480),
    _preset('720p', ShortsQuality.p720),
    _preset('1080p', ShortsQuality.p1080),
    _preset('Best', ShortsQuality.best),
  ];

  ListTile _preset(String title, ShortsQuality q) => ListTile(
    title: Text(title),
    onTap: () async {
      await _controller.setQualityPreset(q);
      if (mounted) Navigator.pop(context);
    },
  );

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Shorts HLS Demo'),
        actions: [
          IconButton(icon: const Icon(Icons.hd), onPressed: _showQualitySheet),
        ],
      ),
      body: _urls.isEmpty
          ? Center(
        child: Text(
          _err.isEmpty
              ? 'Добавь ссылки в assets/demo_urls.txt'
              : _err,
        ),
      )
          : PageView.builder(
        itemCount: _urls.length,
        scrollDirection: Axis.vertical,
        onPageChanged: _changePage,
        itemBuilder: (_, i) => Stack(
          fit: StackFit.expand,
          children: [
            ShortsView(index: i),
            if (_buffering)
              const Center(child: CircularProgressIndicator(strokeWidth: 2)),
            Positioned(
              left: 12,
              right: 12,
              bottom: 12,
              child: _infoBar(),
            ),
          ],
        ),
      ),
    );
  }

  Widget _infoBar() {
    String fmt(int ms) {
      final d = Duration(milliseconds: ms);
      final mm = d.inMinutes.remainder(60).toString().padLeft(2, '0');
      final ss = d.inSeconds.remainder(60).toString().padLeft(2, '0');
      return '$mm:$ss';
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.black54, borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          if (_err.isNotEmpty) ...[
            const Icon(Icons.error, size: 16, color: Colors.redAccent),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                _err,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: Colors.redAccent),
              ),
            ),
          ] else ...[
            const Icon(Icons.play_circle_filled, size: 16),
            const SizedBox(width: 6),
            Text('${fmt(_posMs)} / ${_durMs > 0 ? fmt(_durMs) : '--:--'}'),
          ],
          const Spacer(),
          IconButton(icon: const Icon(Icons.hd), onPressed: _showQualitySheet),
        ],
      ),
    );
  }
}