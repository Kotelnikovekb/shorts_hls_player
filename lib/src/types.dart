enum ShortsQuality { auto, p360, p480, p720, p1080, best }

class ShortsInitConfig {
  final bool looping;
  final bool muted;
  final double volume;
  final ShortsQuality? quality;
  final bool progressEnabled;
  final Duration? progressInterval;

  const ShortsInitConfig({
    this.looping = false,
    this.muted = false,
    this.volume = 1.0,
    this.quality,
    this.progressEnabled = false,
    this.progressInterval,
  });

  Map<String, Object?> toMap() => {
    'looping': looping,
    'muted': muted,
    'volume': volume,
    if (quality != null) 'qualityPreset': quality!.name,
    'progressTracking': {
      'enabled': progressEnabled,
      if (progressInterval != null) 'intervalMs': progressInterval!.inMilliseconds,
    }
  };
}

class ShortsVariant {
  final int? height; // 360, 480, 720...
  final int? width;
  final int? bitrate; // bps
  final String label; // "360p", "Auto", "Best"
  const ShortsVariant({
    this.height,
    this.width,
    this.bitrate,
    required this.label,
  });
}

class ShortsMeta {
  final int? durationMs;
  final int? width;
  final int? height;
  final double? fps;
  final int? avgBitrate;

  const ShortsMeta({
    this.durationMs,
    this.width,
    this.height,
    this.fps,
    this.avgBitrate,
  });
}

sealed class ShortsEvent {
  const ShortsEvent();
}

class OnReady extends ShortsEvent {
  final int index;

  const OnReady(this.index);
}

class OnBufferingStart extends ShortsEvent {
  final int index;

  const OnBufferingStart(this.index);
}

class OnBufferingEnd extends ShortsEvent {
  final int index;

  const OnBufferingEnd(this.index);
}

class OnStall extends ShortsEvent {
  final int index;

  const OnStall(this.index);
}

class OnError extends ShortsEvent {
  final int index;
  final String message;

  const OnError(this.index, this.message);
}

class OnProgress extends ShortsEvent {
  final int index;
  final int posMs;
  final int? durMs;

  const OnProgress(this.index, this.posMs, this.durMs);
}
