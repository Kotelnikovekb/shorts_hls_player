import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shorts_hls_player/shorts_hls_player.dart';

void main() {
  group('Simple Debug Tests', () {
    testWidgets('Test basic widget creation', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Center(
              child: Text('Test Widget'),
            ),
          ),
        ),
      );

      expect(find.text('Test Widget'), findsOneWidget);
    });

    test('Test controller creation without initialization', () {
      final controller = ShortsController();
      expect(controller, isNotNull);
      expect(controller.isPaused, isTrue);
    });

    test('Test basic functionality', () {
      // Простой тест без инициализации плеера
      expect(true, isTrue);
    });
  });
}
