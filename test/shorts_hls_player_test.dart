import 'package:flutter_test/flutter_test.dart';
import 'package:shorts_hls_player/shorts_hls_player.dart';
import 'package:shorts_hls_player/shorts_hls_player_platform_interface.dart';
import 'package:shorts_hls_player/shorts_hls_player_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockShortsHlsPlayerPlatform
    with MockPlatformInterfaceMixin
    implements ShortsHlsPlayerPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final ShortsHlsPlayerPlatform initialPlatform = ShortsHlsPlayerPlatform.instance;

  test('$MethodChannelShortsHlsPlayer is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelShortsHlsPlayer>());
  });

  test('getPlatformVersion', () async {
    ShortsHlsPlayer shortsHlsPlayerPlugin = ShortsHlsPlayer();
    MockShortsHlsPlayerPlatform fakePlatform = MockShortsHlsPlayerPlatform();
    ShortsHlsPlayerPlatform.instance = fakePlatform;

    expect(await shortsHlsPlayerPlugin.getPlatformVersion(), '42');
  });
}
