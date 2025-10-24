import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:shorts_hls_player/shorts_hls_player.dart';

void main() {
  group('Player Initialization Tests', () {
    testWidgets('Test player initialization with timeout', (WidgetTester tester) async {
      final controller = ShortsController();
      
      try {
        // Инициализируем с таймаутом
        await controller.init().timeout(
          Duration(seconds: 5),
          onTimeout: () {
            throw TimeoutException('Player initialization timeout', Duration(seconds: 5));
          },
        );
        
        expect(controller, isNotNull);
        print('Player initialized successfully');
        
        // Очищаем ресурсы
        controller.dispose();
      } catch (e) {
        print('Player initialization failed: $e');
        controller.dispose();
        rethrow;
      }
    });

    testWidgets('Test player with video URL and timeout', (WidgetTester tester) async {
      final controller = ShortsController();
      
      try {
        // Инициализируем с таймаутом
        await controller.init().timeout(
          Duration(seconds: 10),
          onTimeout: () {
            throw TimeoutException('Player initialization timeout', Duration(seconds: 10));
          },
        );
        
        // Добавляем тестовый URL
        await controller.append([
          Uri.parse('https://p2.proxy.zhabby.com/d26e36b13e2ab198ca02e7ab60f61257/manifest/video.m3u8')
        ]).timeout(
          Duration(seconds: 5),
          onTimeout: () {
            throw TimeoutException('URL append timeout', Duration(seconds: 5));
          },
        );
        
        expect(controller, isNotNull);
        print('Player with URL initialized successfully');
        
        // Очищаем ресурсы
        controller.dispose();
      } catch (e) {
        print('Player with URL initialization failed: $e');
        controller.dispose();
        rethrow;
      }
    });
  });
}
