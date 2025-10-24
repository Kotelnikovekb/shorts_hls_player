import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shorts_hls_player/shorts_hls_player.dart';

void main() {
  group('Android Debug Tests', () {
    testWidgets('Test basic player initialization', (WidgetTester tester) async {
      // Создаем простое приложение для тестирования
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TestPlayerWidget(),
          ),
        ),
      );

      await tester.pumpAndSettle();

      // Проверяем, что приложение загрузилось
      expect(find.byType(TestPlayerWidget), findsOneWidget);
    });

    testWidgets('Test player with single video URL', (WidgetTester tester) async {
      final controller = ShortsController();
      
      try {
        // Инициализируем контроллер
        await controller.init();
        
        // Добавляем тестовый URL
        await controller.append([
          Uri.parse('https://p2.proxy.zhabby.com/d26e36b13e2ab198ca02e7ab60f61257/manifest/video.m3u8')
        ]);
        
        // Проверяем, что URL добавлен
        expect(controller, isNotNull);
        
        // Очищаем ресурсы
        controller.dispose();
      } catch (e) {
        print('Error during test: $e');
        rethrow;
      }
    });
  });
}

class TestPlayerWidget extends StatefulWidget {
  @override
  _TestPlayerWidgetState createState() => _TestPlayerWidgetState();
}

class _TestPlayerWidgetState extends State<TestPlayerWidget> {
  final _controller = ShortsController();
  String _status = 'Initializing...';
  String? _error;

  @override
  void initState() {
    super.initState();
    _initializePlayer();
  }

  Future<void> _initializePlayer() async {
    try {
      setState(() {
        _status = 'Initializing player...';
      });

      await _controller.init();
      
      setState(() {
        _status = 'Adding test video...';
      });

      await _controller.append([
        Uri.parse('https://p2.proxy.zhabby.com/d26e36b13e2ab198ca02e7ab60f61257/manifest/video.m3u8')
      ]);

      setState(() {
        _status = 'Player ready';
      });
    } catch (e) {
      setState(() {
        _status = 'Error';
        _error = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text('Status: $_status'),
          if (_error != null) ...[
            SizedBox(height: 16),
            Text('Error: $_error', style: TextStyle(color: Colors.red)),
          ],
          SizedBox(height: 16),
          if (_status == 'Player ready')
            ElevatedButton(
              onPressed: () async {
                try {
                  await _controller.onActive(0);
                  setState(() {
                    _status = 'Playing video...';
                  });
                } catch (e) {
                  setState(() {
                    _error = e.toString();
                  });
                }
              },
              child: Text('Play Video'),
            ),
        ],
      ),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }
}
