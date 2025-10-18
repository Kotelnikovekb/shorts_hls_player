import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shorts_hls_player_example/main.dart';

void main() {
  testWidgets('DemoApp renders home scaffold', (WidgetTester tester) async {
    await tester.pumpWidget(const DemoApp());
    await tester.pumpAndSettle();

    expect(find.byType(MaterialApp), findsOneWidget);
    expect(find.text('Shorts HLS Demo • Pagination'), findsOneWidget);
  });
}
