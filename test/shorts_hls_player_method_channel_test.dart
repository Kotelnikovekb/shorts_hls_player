import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shorts_hls_player/shorts_hls_player.dart';
import 'package:shorts_hls_player/src/method_channel_impl.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('shorts_hls_player/methods');
  final recordedCalls = <MethodCall>[];
  late MethodChannelShorts platform;

  setUp(() {
    recordedCalls.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall call) async {
      recordedCalls.add(call);
      if (call.method == 'getThumbnail') {
        return Uint8List.fromList([1, 2, 3]);
      }
      return null;
    });
    platform = MethodChannelShorts();
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('init forwards config to native layer', () async {
    await platform.init(
      config: const ShortsInitConfig(looping: true, muted: true),
    );

    expect(recordedCalls, isNotEmpty);
    final call = recordedCalls.first;
    expect(call.method, 'init');
    expect(call.arguments, containsPair('looping', true));
    expect(call.arguments, containsPair('muted', true));
  });

  test('getThumbnail returns channel payload', () async {
    final bytes = await platform.getThumbnail(3);

    expect(bytes, Uint8List.fromList([1, 2, 3]));
    expect(
      recordedCalls.where((c) => c.method == 'getThumbnail'),
      isNotEmpty,
    );
    final args = recordedCalls.last.arguments as Map<dynamic, dynamic>;
    expect(args['index'], 3);
  });
}
