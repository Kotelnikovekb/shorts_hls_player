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

  init(frame: CGRect, index: Int, pool: PlayerPool) {
    super.init()
    container.frame = frame
    pool.bindView(index: index, view: container)
  }

  func view() -> UIView { container }
}

/// Простой UIView, который подгоняет слой при изменении размера
final class PlayerHostingView: UIView {
  private let placeholderView: UIImageView = {
    let view = UIImageView()
    view.contentMode = .scaleAspectFill
    view.clipsToBounds = true
    view.backgroundColor = .black
    return view
  }()
  private var playerLayer: AVPlayerLayer?

  override init(frame: CGRect) {
    super.init(frame: frame)
    addSubview(placeholderView)
    placeholderView.frame = bounds
    placeholderView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    // Показываем черный фон сразу, чтобы не было прозрачности
    placeholderView.backgroundColor = .black
    placeholderView.isHidden = false
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    addSubview(placeholderView)
    placeholderView.frame = bounds
    placeholderView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    // Показываем черный фон сразу, чтобы не было прозрачности
    placeholderView.backgroundColor = .black
    placeholderView.isHidden = false
  }

  func setPlaceholder(_ image: UIImage?) {
    placeholderView.image = image
    // Всегда показываем плейсхолдер (с изображением или черным фоном)
    placeholderView.isHidden = false
    
    // Плавный fade-in для превью
    if image != nil {
      placeholderView.alpha = 0.0
      UIView.animate(withDuration: 0.2, delay: 0, options: [.curveEaseOut], animations: {
        self.placeholderView.alpha = 1.0
      })
    }
  }

  func hidePlaceholder() {
    // Плавный fade-out для превью
    UIView.animate(withDuration: 0.3, delay: 0, options: [.curveEaseOut], animations: {
      self.placeholderView.alpha = 0.0
    }) { _ in
      self.placeholderView.isHidden = true
      self.placeholderView.alpha = 1.0 // Восстанавливаем для следующего использования
    }
  }

  func setPlayerLayer(_ layer: AVPlayerLayer) {
    playerLayer?.removeFromSuperlayer()
    playerLayer = layer
    layer.frame = bounds
    layer.videoGravity = .resizeAspectFill
    // вставляем под placeholder, чтобы он оставался видимым пока не спрячем
    layer.zPosition = -1
    self.layer.insertSublayer(layer, below: placeholderView.layer)
    
    // Убираем анимацию появления видео и делаем плавный fade-in
    layer.removeAllAnimations()
    layer.opacity = 0.0
    
    // Плавный fade-in для видео
    CATransaction.begin()
    CATransaction.setAnimationDuration(0.3)
    CATransaction.setAnimationTimingFunction(CAMediaTimingFunction(name: .easeOut))
    layer.opacity = 1.0
    CATransaction.commit()
  }

  func clearPlayerLayer() {
    playerLayer?.removeFromSuperlayer()
    playerLayer = nil
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    placeholderView.frame = bounds
    playerLayer?.frame = bounds
  }
}
