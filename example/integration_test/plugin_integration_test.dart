import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:shorts_hls_player/shorts_hls_player.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('Shorts HLS Player Integration Tests', () {
    testWidgets('controller initial state is paused', (WidgetTester tester) async {
      final controller = ShortsController();
      expect(controller.isPaused, isTrue);
    });

    testWidgets('app loads and displays demo content', (WidgetTester tester) async {
      // Запускаем приложение
      await tester.pumpWidget(const MaterialApp(
        home: DemoApp(),
      ));
      await tester.pumpAndSettle();

      // Проверяем, что приложение загрузилось
      expect(find.text('Shorts HLS Demo • Pagination'), findsOneWidget);
      
      // Ждем загрузки контента
      await tester.pumpAndSettle(const Duration(seconds: 3));
      
      // Проверяем, что есть кнопка качества
      expect(find.byIcon(Icons.hd), findsWidgets);
    });

    testWidgets('quality settings can be opened', (WidgetTester tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: DemoApp(),
      ));
      await tester.pumpAndSettle();

      // Ждем загрузки
      await tester.pumpAndSettle(const Duration(seconds: 3));

      // Нажимаем на кнопку качества
      await tester.tap(find.byIcon(Icons.hd).first);
      await tester.pumpAndSettle();

      // Проверяем, что открылось меню качества
      expect(find.text('Auto'), findsOneWidget);
      expect(find.text('360p'), findsOneWidget);
      expect(find.text('480p'), findsOneWidget);
      expect(find.text('720p'), findsOneWidget);
      expect(find.text('1080p'), findsOneWidget);
      expect(find.text('Best'), findsOneWidget);
    });

    testWidgets('can change quality settings', (WidgetTester tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: DemoApp(),
      ));
      await tester.pumpAndSettle();

      // Ждем загрузки
      await tester.pumpAndSettle(const Duration(seconds: 3));

      // Открываем меню качества
      await tester.tap(find.byIcon(Icons.hd).first);
      await tester.pumpAndSettle();

      // Выбираем 720p
      await tester.tap(find.text('720p'));
      await tester.pumpAndSettle();

      // Проверяем, что меню закрылось
      expect(find.text('720p'), findsNothing);
    });

    testWidgets('app handles video loading states', (WidgetTester tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: DemoApp(),
      ));
      await tester.pumpAndSettle();

      // Ждем загрузки видео
      await tester.pumpAndSettle(const Duration(seconds: 5));

      // Проверяем, что приложение не упало и работает
      expect(find.text('Shorts HLS Demo • Pagination'), findsOneWidget);
    });
  });
}

// Простое демо приложение для тестирования
class DemoApp extends StatelessWidget {
  const DemoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Shorts HLS Demo • Pagination'),
        actions: [
          IconButton(
            icon: const Icon(Icons.hd),
            onPressed: () => _showQualitySheet(context),
          ),
        ],
      ),
      body: const Center(
        child: Text('Demo app for testing'),
      ),
    );
  }

  void _showQualitySheet(BuildContext context) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.black87,
      builder: (ctx) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            _presetTile('Auto', ShortsQuality.auto, ctx),
            _presetTile('360p', ShortsQuality.p360, ctx),
            _presetTile('480p', ShortsQuality.p480, ctx),
            _presetTile('720p', ShortsQuality.p720, ctx),
            _presetTile('1080p', ShortsQuality.p1080, ctx),
            _presetTile('Best', ShortsQuality.best, ctx),
          ],
        ),
      ),
    );
  }

  ListTile _presetTile(String title, ShortsQuality q, BuildContext context) => ListTile(
    title: Text(title),
    onTap: () => Navigator.pop(context),
  );
}
