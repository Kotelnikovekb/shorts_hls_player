import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'platform_interface.dart';

class ShortsView extends StatefulWidget {
  final int index;
  const ShortsView({super.key, required this.index});
  @override State<ShortsView> createState() => _ShortsViewState();
}

class _ShortsViewState extends State<ShortsView> {
  int? _viewId;
  @override
  void initState() {
    super.initState();
    _init();
  }
  Future<void> _init() async {
    _viewId = await ShortsPlatform.instance.createView(widget.index);
    if (mounted) setState(() {});
  }
  @override
  Widget build(BuildContext context) {
    if (_viewId == null) {
      return const ColoredBox(color: Colors.black);
    }
    // iOS UiKitView binding:
    return UiKitView(
      viewType: 'shorts_hls_player/view',
      creationParams: {'index': widget.index},
      creationParamsCodec: const StandardMessageCodec(),
    );
  }
}