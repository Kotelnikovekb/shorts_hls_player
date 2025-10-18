import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:shorts_hls_player/shorts_hls_player.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('controller initial state is paused', (WidgetTester tester) async {
    final controller = ShortsController();
    expect(controller.isPaused, isTrue);
  });
}
