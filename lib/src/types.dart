enum ShortsQuality { auto, p360, p480, p720, p1080, best }

class VideoMeta {
  final int width;
  final int height;
  final int durationMs;

  const VideoMeta(
      {required this.width, required this.height, required this.durationMs});

  bool get isVertical => height > width;

  double? get aspect => width > 0 ? height / width : null;
}

class VideoPerformanceMetrics {
  final int startupMs;
  final int firstFrameMs;
  final int rebufferCount;
  final int totalRebufferMs;
  final double averageRebufferMs;

  const VideoPerformanceMetrics({
    required this.startupMs,
    required this.firstFrameMs,
    required this.rebufferCount,
    required this.totalRebufferMs,
  }) : averageRebufferMs = rebufferCount > 0 ? totalRebufferMs / rebufferCount : 0.0;
}

class ShortsInitConfig {
  final bool looping;
  final bool muted;
  final double volume;
  final ShortsQuality? quality;
  final bool progressEnabled;
  final Duration? progressInterval;
  final int? maxActivePlayers;
  final int? prefetchBytesLimit;
  final Duration? forwardBufferDuration;

  const ShortsInitConfig({
    this.looping = false,
    this.muted = false,
    this.volume = 1.0,
    this.quality,
    this.progressEnabled = false,
    this.progressInterval,
    this.maxActivePlayers,
    this.prefetchBytesLimit,
    this.forwardBufferDuration,
  });

  Map<String, Object?> toMap() => {
        'looping': looping,
        'muted': muted,
        'volume': volume,
        if (quality != null) 'qualityPreset': quality!.name,
        'progressTracking': {
          'enabled': progressEnabled,
          if (progressInterval != null)
            'intervalMs': progressInterval!.inMilliseconds,
        },
        if (maxActivePlayers != null) 'maxActivePlayers': maxActivePlayers,
        if (prefetchBytesLimit != null)
          'prefetchBytesLimit': prefetchBytesLimit,
        if (forwardBufferDuration != null)
          'forwardBufferSeconds':
              forwardBufferDuration!.inMilliseconds / 1000.0,
      };
}

class ShortsVariant {
  final int? height;
  final int? width;
  final int? bitrate;
  final String label;
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
  final int? bufferedMs;

  const OnProgress(this.index, this.posMs, this.durMs, this.bufferedMs);
}

class ReadyEvent extends ShortsEvent {
  final int index;
  const ReadyEvent(this.index);
}

class BufferingStartEvent extends ShortsEvent {
  final int index;
  const BufferingStartEvent(this.index);
}

class BufferingEndEvent extends ShortsEvent {
  final int index;
  const BufferingEndEvent(this.index);
}

class FirstFrameEvent extends ShortsEvent {
  final int index;
  const FirstFrameEvent(this.index);
}

class ProgressEvent extends ShortsEvent {
  final int index;
  final Duration position;
  final Duration duration;
  final int? bufferedMs;
  const ProgressEvent(this.index, this.position, this.duration, {this.bufferedMs});
}

class MetricsEvent extends ShortsEvent {
  final int index;
  final int? startupMs;
  final int? firstFrameMs;
  final int rebufferCount;
  final int rebufferDurationMs;
  final int? lastRebufferDurationMs;
  const MetricsEvent(
    this.index, {
    this.startupMs,
    this.firstFrameMs,
    this.rebufferCount = 0,
    this.rebufferDurationMs = 0,
    this.lastRebufferDurationMs,
  });
}

class CompletedEvent extends ShortsEvent {
  final int index;
  const CompletedEvent(this.index);
}

class ErrorEvent extends ShortsEvent {
  final int index;
  final String message;
  const ErrorEvent(this.index, this.message);
}

class UnknownEvent extends ShortsEvent {
  final Map<String, dynamic> raw;
  const UnknownEvent(this.raw);
}

ShortsEvent parseShortsEvent(Map<dynamic, dynamic> map) {
  final type = (map['type'] ?? map['event']) as String? ?? 'unknown';
  final idx = (map['index'] as num?)?.toInt() ?? -1;

  switch (type) {
    case 'ready':
      return ReadyEvent(idx);
    case 'bufferingStart':
      return BufferingStartEvent(idx);
    case 'bufferingEnd':
      return BufferingEndEvent(idx);
    case 'firstFrame':
      return FirstFrameEvent(idx);
    case 'progress':
      final pos = (map['posMs'] ?? map['position'] ?? 0) as int;
      final dur = (map['durMs'] ?? map['duration'] ?? -1) as int;
      final buf = (map['bufMs'] ?? map['bufferedMs']) as int?;
      return ProgressEvent(
        idx,
        Duration(milliseconds: pos),
        Duration(milliseconds: dur),
        bufferedMs: buf,
      );
    case 'metrics':
      return MetricsEvent(
        idx,
        startupMs: (map['startupMs'] as num?)?.toInt(),
        firstFrameMs: (map['firstFrameMs'] as num?)?.toInt(),
        rebufferCount: (map['rebufferCount'] as num?)?.toInt() ?? 0,
        rebufferDurationMs: (map['rebufferDurationMs'] as num?)?.toInt() ?? 0,
        lastRebufferDurationMs: (map['lastRebufferDurationMs'] as num?)?.toInt(),
      );
    case 'completed':
    case 'watched':
      return CompletedEvent(idx);
    case 'error':
      return ErrorEvent(idx, map['message']?.toString() ?? 'unknown');
    default:
      return UnknownEvent(map.map((k, v) => MapEntry(k.toString(), v)));
  }
}
