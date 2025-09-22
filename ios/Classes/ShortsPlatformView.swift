import Flutter
import UIKit
import AVFoundation

final class ShortsPlatformViewFactory: NSObject, FlutterPlatformViewFactory {
  private let pool: PlayerPool
  private let messenger: FlutterBinaryMessenger

  init(pool: PlayerPool, messenger: FlutterBinaryMessenger) {
    self.pool = pool
    self.messenger = messenger
    super.init()
  }

  // iOS codec для creationParams
  func createArgsCodec() -> (FlutterMessageCodec & NSObjectProtocol) {
    FlutterStandardMessageCodec.sharedInstance()
  }

  func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
    let index = (args as? [String: Any])?["index"] as? Int ?? 0
    return ShortsPlatformView(frame: frame, index: index, pool: pool)
  }
}

final class ShortsPlatformView: NSObject, FlutterPlatformView {
  private let container = PlayerHostingView()
  private var playerLayer: AVPlayerLayer?

  init(frame: CGRect, index: Int, pool: PlayerPool) {
    super.init()
    container.frame = frame

    if let entry = pool.entry(for: index) {
      let layer = AVPlayerLayer(player: entry.player)
      layer.videoGravity = .resizeAspectFill
      layer.frame = container.bounds
      container.layer.addSublayer(layer)
      self.playerLayer = layer
    }
  }

  func view() -> UIView { container }
}

/// Простой UIView, который подгоняет слой при изменении размера
private final class PlayerHostingView: UIView {
  override func layoutSubviews() {
    super.layoutSubviews()
    layer.sublayers?.forEach { $0.frame = bounds }
  }
}
