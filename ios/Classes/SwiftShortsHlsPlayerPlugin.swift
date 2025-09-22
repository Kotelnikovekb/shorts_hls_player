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
      pool.configure()
      result(nil)

    case "appendUrls":
      if let args = call.arguments as? [String: Any],
         let urls = args["urls"] as? [String] {
        pool.append(urls: urls)
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
      // Для UiKitView сам view создаёт фабрика, тут можно вернуть любой int.
      result(0)

    default:
      result(FlutterMethodNotImplemented)
    }
  }
}
