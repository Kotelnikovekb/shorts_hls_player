import 'dart:io' show Platform;
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'platform_interface.dart';

class ShortsView extends StatefulWidget {
  final int index;
  const ShortsView({super.key, required this.index});

  @override
  State<ShortsView> createState() => _ShortsViewState();
}

class _ShortsViewState extends State<ShortsView> {
  int? _textureId;
  bool _attaching = false;

  @override
  void initState() {
    super.initState();
    if (Platform.isAndroid) {
      _createAndroidTexture();
    }
  }

  @override
  void didUpdateWidget(covariant ShortsView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (Platform.isAndroid && oldWidget.index != widget.index) {
      _createAndroidTexture();
    }
  }

  Future<void> _createAndroidTexture() async {
    if (_attaching) return;
    _attaching = true;
    try {
      final id = await ShortsPlatform.instance.createView(widget.index);
      await ShortsPlatform.instance.setCurrent(widget.index);
      if (!mounted) return;
      setState(() => _textureId = id);
    } on PlatformException {
      if (!mounted) return;
      setState(() => _textureId = null);
    } finally {
      _attaching = false;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (Platform.isIOS) {
      return UiKitView(
        viewType: 'shorts_hls_player/view',
        creationParams: {'index': widget.index},
        creationParamsCodec: const StandardMessageCodec(),
      );
    }

    if (_textureId == null) {
      return const ColoredBox(color: Colors.black);
    }
    return Texture(textureId: _textureId!);
  }
}