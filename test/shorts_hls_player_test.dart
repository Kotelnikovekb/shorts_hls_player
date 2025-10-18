import 'package:flutter_test/flutter_test.dart';
import 'package:shorts_hls_player/src/method_channel_impl.dart';
import 'package:shorts_hls_player/src/platform_interface.dart';

void main() {
  test('ShortsPlatform default instance uses method channel', () {
    expect(ShortsPlatform.instance, isA<MethodChannelShorts>());
  });
}
