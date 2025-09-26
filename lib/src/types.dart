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
          if (progressInterval != null)
            'intervalMs': progressInterval!.inMilliseconds,
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

// ↓ Добавляем конкретные типы событий:
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
  const ProgressEvent(this.index, this.position, this.duration);
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

// Универсальный парсер из map (что прилетает из EventChannel):
ShortsEvent parseShortsEvent(Map<dynamic, dynamic> map) {
  // поддержим оба ключа: "type" и "event"
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
      return ProgressEvent(idx, Duration(milliseconds: pos), Duration(milliseconds: dur));
    case 'completed':
    case 'watched': // если с нативной стороны шлёшь watched
      return CompletedEvent(idx);
    case 'error':
      return ErrorEvent(idx, map['message']?.toString() ?? 'unknown');
    default:
      return UnknownEvent(map.map((k, v) => MapEntry(k.toString(), v)));
  }
}