import Flutter
import UIKit
import AVFoundation

@objc(ShortsHlsPlayerPlugin)
public class SwiftShortsHlsPlayerPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {

  private var methodChannel: FlutterMethodChannel!
  private var eventChannel: FlutterEventChannel!
  private var eventSink: FlutterEventSink?

  private let pool = PlayerPool()

  public static func register(with registrar: FlutterPluginRegistrar) {
    let instance = SwiftShortsHlsPlayerPlugin()

    instance.methodChannel = FlutterMethodChannel(
      name: "shorts_hls_player/methods",
      binaryMessenger: registrar.messenger()
    )
    registrar.addMethodCallDelegate(instance, channel: instance.methodChannel)

    instance.eventChannel = FlutterEventChannel(
      name: "shorts_hls_player/events",
      binaryMessenger: registrar.messenger()
    )
    instance.eventChannel.setStreamHandler(instance)

    // UiKitView factory
    let factory = ShortsPlatformViewFactory(pool: instance.pool, messenger: registrar.messenger())
    registrar.register(factory, withId: "shorts_hls_player/view")
  }

  // MARK: - FlutterStreamHandler
  public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
    self.eventSink = events
    pool.onEvent = { [weak self] evt in
      self?.eventSink?(evt)
    }
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    self.eventSink = nil
    pool.onEvent = nil
    return nil
  }

  // MARK: - Methods
  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {

    case "init":
      if let args = call.arguments as? [String: Any] {
        pool.configure()
        if let looping = args["looping"] as? Bool { pool.setLooping(looping) }
        if let muted = args["muted"] as? Bool { pool.setMuted(muted) }
        if let volume = args["volume"] as? Double { pool.setVolume(Float(volume)) }
        if let buffer = args["forwardBufferSeconds"] as? Double {
          pool.setForwardBuffer(seconds: buffer)
        } else if let bufferInt = args["forwardBufferSeconds"] as? Int {
          pool.setForwardBuffer(seconds: Double(bufferInt))
        }
        if let progress = args["progressTracking"] as? [String: Any] {
          let enabled = progress["enabled"] as? Bool ?? false
          let interval = progress["intervalMs"] as? Int
          pool.setProgressTracking(enabled: enabled, intervalMs: interval)
        }
      } else {
        pool.configure()
      }
      result(nil)

    case "appendUrls":
      if let args = call.arguments as? [String: Any],
         let urls = args["urls"] as? [String] {
        if let replace = args["replace"] as? Bool, replace {
          pool.replace(urls: urls)
        } else {
          pool.append(urls: urls)
        }
      }
      result(nil)

    case "disposeIndex":
      let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
      pool.dispose(index: idx)
      result(nil)

    case "setCurrent":
      let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
      pool.setCurrent(index: idx)
      result(nil)
        
    case "getThumbnail":
      let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
      self.pool.getThumbnail(index: idx) { data in
        if let data = data {
          result(FlutterStandardTypedData(bytes: data))
        } else {
          result(nil)
        }
      }

    case "play":
      let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
      pool.play(index: idx)
      result(nil)

    case "pause":
      let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
      pool.pause(index: idx)
      result(nil)

    case "prime":
      let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
      pool.prime(index: idx)
      result(nil)

    case "setQualityPreset":
      let preset = (call.arguments as? [String: Any])?["preset"] as? String ?? "auto"
      pool.setQuality(preset: preset)
      result(nil)

    case "getVariants":
      let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
      result(pool.getVariants(index: idx))

    case "getMeta":
      let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
      result(pool.getMeta(index: idx))

    case "createView":
      result(0)

    case "getPlaybackInfo":
      let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
      let info = pool.getPlaybackInfo(index: idx)
      result(info)

    case "togglePlayPause":
          let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
          pool.togglePlayPause(index: idx); result(nil)

        case "setMuted":
          let muted = (call.arguments as? [String: Any])?["muted"] as? Bool ?? false
          pool.setMuted(muted); result(nil)

        case "setVolume":
          let vol = (call.arguments as? [String: Any])?["volume"] as? Double ?? 1.0
          pool.setVolume(Float(vol)); result(nil)

        case "setLooping":
          let loop = (call.arguments as? [String: Any])?["looping"] as? Bool ?? false
          pool.setLooping(loop); result(nil)

        case "isPaused":
          let idx = (call.arguments as? [String: Any])?["index"] as? Int ?? -1
          result(pool.isPaused(index: idx))

        case "setProgressTracking":
          let args = call.arguments as? [String: Any]
          let enabled = args?["enabled"] as? Bool ?? false
          let intervalMs = args?["intervalMs"] as? Int
          pool.setProgressTracking(enabled: enabled, intervalMs: intervalMs)
          result(nil)


    default:
      result(FlutterMethodNotImplemented)
    }
  }
}
