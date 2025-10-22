import AVFoundation
import UIKit

private final class WeakPlayerView {
  weak var view: PlayerHostingView?
  init(_ view: PlayerHostingView) {
    self.view = view
  }
}

final class PlayerPool {

  struct Entry {
    let url: URL
    let host: PlayerItemHost
    var player: AVPlayer { host.player }
    var item: AVPlayerItem { host.item }
    var layer: AVPlayerLayer?
  }

  private var entries: [Int: Entry] = [:]
  private var urls: [URL] = []
  private var currentIndex: Int = -1
  var onEvent: (([String: Any]) -> Void)?
  private var forwardBufferSeconds: TimeInterval?
  private var boundViews: [Int: WeakPlayerView] = [:]
    
    private var isLooping = false
    private var isMuted = false
    private var volume: Float = 1.0
    
    
    private var progressEnabled = false
    private var progressIntervalMs = 500
    private var watchedFired: Set<Int> = []
    
    weak var methodInvoker: MethodInvoker?


  private let thumbCache = NSCache<NSNumber, NSData>()
  private let thumbnailSampleSeconds: [Double] = [0.0, 0.12, 0.3, 0.6, 1.0, 1.6]
  private lazy var thumbnailSampleTimes: [CMTime] = thumbnailSampleSeconds.map {
    CMTime(seconds: $0, preferredTimescale: 600)
  }
  private let luminanceThreshold: Double = 0.035

  func getThumbnail(index: Int, completion: @escaping (Data?) -> Void) {
      guard index >= 0, index < urls.count else { completion(nil); return }

      if let cached = thumbCache.object(forKey: NSNumber(value: index)) {
        completion(Data(referencing: cached))
        return
      }

      let asset: AVURLAsset
      if let e = entries[index] {
        asset = e.host.asset
      } else {
        asset = AVURLAsset(url: urls[index])
      }

      DispatchQueue.global(qos: .userInitiated).async { [weak self] in
        guard let self = self else {
          DispatchQueue.main.async { completion(nil) }
          return
        }

        let dataOut = self.makeThumbnail(from: asset)
        if let d = dataOut {
          self.thumbCache.setObject(d as NSData, forKey: NSNumber(value: index))
          #if DEBUG
          print("Thumbnail captured index=\(index) bytes=\(d.count)")
          #endif
        } else {
          #if DEBUG
          print("Thumbnail extraction failed for index \(index)")
          #endif
        }

        DispatchQueue.main.async {
          completion(dataOut)
        }
      }
    }

  func configure() {
    // setCategory — throws, используем try?
    try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [.mixWithOthers])
  }

  func append(urls: [String]) {
    self.urls.append(contentsOf: urls.compactMap { URL(string: $0) })
  }

  func replace(urls: [String]) {
    self.urls = urls.compactMap { URL(string: $0) }
    entries.removeAll()
    currentIndex = -1
    watchedFired.removeAll()
    for view in boundViews.values {
      if let v = view.view {
        let reset = {
          v.clearPlayerLayer()
          v.setPlaceholder(nil)
        }
        if Thread.isMainThread {
          reset()
        } else {
          DispatchQueue.main.async(execute: reset)
        }
      }
    }
    // Прогреем превью для первых элементов, чтобы UiKitView сразу показал кадр
    let prefetchCount = min(3, self.urls.count)
    if prefetchCount > 0 {
      for i in 0..<prefetchCount {
        if thumbCache.object(forKey: NSNumber(value: i)) == nil {
          getThumbnail(index: i) { _ in }
        }
      }
    }
  }

  func entry(for index: Int) -> Entry? { entries[index] }


  func prime(index: Int) {
    guard index >= 0 && index < urls.count else { return }
    if entries[index] != nil { return }

    let url = urls[index]
    let host = PlayerItemHost(index: index, url: url)
    host.forwardBufferDuration = forwardBufferSeconds

    host.player.isMuted = isMuted
    host.player.volume = volume
    host.progressInterval = progressIntervalMs

    host.onReady = { [weak self] i in self?.send(["type":"ready","index": i]) }
    host.onBufferingStart = { [weak self] i in self?.send(["type":"bufferingStart","index": i]) }
    host.onBufferingEnd = { [weak self] i in self?.send(["type":"bufferingEnd","index": i]) }
    host.onStall = { [weak self] i in self?.send(["type":"stall","index": i]) }
    host.onError = { [weak self] i, msg in self?.send(["type":"error","index": i, "message": msg]) }
    host.onFirstFrame = { [weak self] i in
      guard let self = self else { return }
      self.send(["type":"firstFrame","index": i])
      // Плавно скрываем превью при первом кадре видео
      if Thread.isMainThread {
        self.boundViews[i]?.view?.hidePlaceholder()
      } else {
        DispatchQueue.main.async { [weak self] in
          self?.boundViews[i]?.view?.hidePlaceholder()
        }
      }
    }

    host.onProgress = { [weak self] idx, posMs, durMs in
      guard let self = self else { return }
      self.send(["type":"progress","index": idx, "posMs": posMs, "durMs": durMs as Any])

      if posMs > 0 {
        // Плавно скрываем превью при начале воспроизведения
        if Thread.isMainThread {
          self.boundViews[idx]?.view?.hidePlaceholder()
        } else {
          DispatchQueue.main.async { [weak self] in
            self?.boundViews[idx]?.view?.hidePlaceholder()
          }
        }
      }

      if self.progressEnabled, let e = self.entries[idx] {
        let bufferedMs = Int(self.bufferedDurationMs(for: e.item))
        self.methodInvoker?.invoke(
          name: "onProgress",
          args: ["index": idx, "url": e.url.absoluteString, "positionMs": posMs, "durationMs": durMs ?? -1, "bufferedMs": bufferedMs]
        )
        if durMs != nil, durMs! > 0, !self.watchedFired.contains(idx) {
          if Double(posMs) >= 0.95 * Double(durMs!) {
            self.watchedFired.insert(idx)
            self.methodInvoker?.invoke(
              name: "onWatched",
              args: ["index": idx, "url": e.url.absoluteString]
            )
          }
        }
      }
    }

    host.onCompleted = { [weak self] idx in
      guard let self = self, self.isLooping, let e = self.entries[idx] else { return }
      e.player.seek(to: .zero)
      e.player.play()
    }

    let entry = Entry(url: url, host: host)
    entries[index] = entry

    host.prime()
    attachViewIfPossible(index: index)

    if thumbCache.object(forKey: NSNumber(value: index)) == nil {
      getThumbnail(index: index) { _ in }
    }
  }

  func dispose(index: Int) {
    if let view = boundViews[index]?.view {
      let clear = {
        view.clearPlayerLayer()
        view.setPlaceholder(nil)
      }
      if Thread.isMainThread {
        clear()
      } else {
        DispatchQueue.main.async(execute: clear)
      }
    }
    entries.removeValue(forKey: index) // deinit host освободит ресурсы
    boundViews.removeValue(forKey: index)
  }
    
    func setForwardBuffer(seconds: TimeInterval?) {
      let sanitized = seconds.map { max(0.0, $0) }
      forwardBufferSeconds = sanitized
      entries.values.forEach { $0.host.forwardBufferDuration = sanitized }
    }
    
    func togglePlayPause(index: Int) {
      guard let p = entries[index]?.player else { return }
      if p.rate == 0 { p.play() } else { p.pause() }
    }
    
    
    func setMuted(_ value: Bool) {
      isMuted = value
      entries.values.forEach { $0.player.isMuted = value }
    }
    
    func setVolume(_ value: Float) {
      volume = max(0.0, min(1.0, value))
      entries.values.forEach { $0.player.volume = volume }
    }

    func setLooping(_ value: Bool) { isLooping = value }

    func isPaused(index: Int) -> Bool {
      guard let p = entries[index]?.player else { return true }
      return p.rate == 0
    }

  func setCurrent(index: Int) {
    currentIndex = index
    prime(index: index)
    // ограничиваем окно: активный + соседи
    for key in entries.keys where abs(key - index) > 1 {
      dispose(index: key)
    }
    send(["type":"ready","index": index])
    attachViewIfPossible(index: index)
  }
    func setProgressTracking(enabled: Bool, intervalMs: Int?) {
      progressEnabled = enabled
      if let ms = intervalMs { progressIntervalMs = ms }
      // обновим у активных хостов интервал таймера
      entries.values.forEach { $0.host.progressInterval = progressIntervalMs }
      if !enabled { watchedFired.removeAll() }
    }

  func play(index: Int) { entries[index]?.player.play() }
  func pause(index: Int) { entries[index]?.player.pause() }
  
  func bindView(index: Int, view: PlayerHostingView) {
    boundViews[index] = WeakPlayerView(view)
    // Сразу показываем черный фон, чтобы не было прозрачности
    view.setPlaceholder(nil)

    if let cached = thumbCache.object(forKey: NSNumber(value: index)) {
      let data = Data(referencing: cached)
      let apply = {
        view.setPlaceholder(UIImage(data: data))
      }
      if Thread.isMainThread {
        apply()
      } else {
        DispatchQueue.main.async(execute: apply)
      }
    } else {
      // Для первых элементов пытаемся синхронно получить превью
      if index < 3 {
        generateThumbnailSync(index: index) { [weak self] data in
          guard let self = self else { return }
          let apply = {
            guard
              let targetView = self.boundViews[index]?.view,
              let bytes = data,
              let image = UIImage(data: bytes)
            else { return }
            targetView.setPlaceholder(image)
          }
          if Thread.isMainThread {
            apply()
          } else {
            DispatchQueue.main.async(execute: apply)
          }
        }
      } else {
        getThumbnail(index: index) { [weak self] data in
          guard let self = self else { return }
          let apply = {
            guard
              let targetView = self.boundViews[index]?.view,
              let bytes = data,
              let image = UIImage(data: bytes)
            else { return }
            targetView.setPlaceholder(image)
          }
          if Thread.isMainThread {
            apply()
          } else {
            DispatchQueue.main.async(execute: apply)
          }
        }
      }
    }

    attachViewIfPossible(index: index)
  }

  private func attachViewIfPossible(index: Int) {
    guard let view = boundViews[index]?.view else { return }
    guard let player = entries[index]?.player else { return }

    let layer: AVPlayerLayer
    if let existing = entries[index]?.layer {
      layer = existing
      layer.player = player
    } else {
      layer = AVPlayerLayer(player: player)
      storeLayer(layer, for: index)
    }

    let applyLayer = {
      view.setPlayerLayer(layer)
    }

    if Thread.isMainThread {
      applyLayer()
    } else {
      DispatchQueue.main.async(execute: applyLayer)
    }
  }

  private func updateEntry(_ index: Int, mutate: (inout Entry) -> Void) {
    guard var entry = entries[index] else { return }
    mutate(&entry)
    entries[index] = entry
  }

  private func storeLayer(_ layer: AVPlayerLayer, for index: Int) {
    updateEntry(index) { $0.layer = layer }
  }

  func setQuality(preset: String) {
    guard let qp = QualityPreset(rawValue: preset) else { return }
    for entry in entries.values {
      entry.host.applyQuality(qp)
    }
  }

  func getMeta(index: Int) -> [String: Any] {
    guard let e = entries[index] else { return [:] }
    return e.host.getMeta()
  }

  func getVariants(index: Int) -> [[String: Any]] {
    guard let e = entries[index] else { return [["label":"Auto"]] }
    return e.host.getVariants()
  }

  private func send(_ payload: [String: Any]) { onEvent?(payload) }
  
  // Синхронная генерация превью для первых элементов
  private func generateThumbnailSync(index: Int, completion: @escaping (Data?) -> Void) {
    guard index >= 0, index < urls.count else { completion(nil); return }
    
    if let cached = thumbCache.object(forKey: NSNumber(value: index)) {
      completion(Data(referencing: cached))
      return
    }
    
    let asset: AVAsset = entries[index]?.host.asset ?? AVURLAsset(url: urls[index])

    DispatchQueue.global(qos: .userInitiated).async { [weak self] in
      guard let self = self else {
        DispatchQueue.main.async { completion(nil) }
        return
      }

      let data = self.makeThumbnail(from: asset)
      if let data = data {
        self.thumbCache.setObject(data as NSData, forKey: NSNumber(value: index))
      } else {
        #if DEBUG
        print("Thumbnail sync extraction failed for index \(index)")
        #endif
      }

      DispatchQueue.main.async {
        completion(data)
      }
    }
  }
  
  private func bufferedDurationMs(for item: AVPlayerItem) -> Double {
        guard let range = item.loadedTimeRanges.first?.timeRangeValue else { return 0 }
        let end = CMTimeGetSeconds(range.start) + CMTimeGetSeconds(range.duration)
        let cur = CMTimeGetSeconds(item.currentTime())
        return max(0, (end - cur) * 1000.0)
      }
}

extension PlayerPool {
  private func configuredGenerator(for asset: AVAsset) -> AVAssetImageGenerator {
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.maximumSize = CGSize(width: 540, height: 540)
    let tolerance = CMTime(seconds: 0.25, preferredTimescale: 600)
    generator.requestedTimeToleranceBefore = tolerance
    generator.requestedTimeToleranceAfter = tolerance
    return generator
  }

  private func makeThumbnail(from asset: AVAsset) -> Data? {
    let generator = configuredGenerator(for: asset)
    for time in thumbnailSampleTimes {
      guard let cgImage = try? generator.copyCGImage(at: time, actualTime: nil) else {
        continue
      }
      if frameHasContent(cgImage),
         let data = UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.85) {
        return data
      }
    }
    return nil
  }

  private func frameHasContent(_ cgImage: CGImage) -> Bool {
    guard let luminance = averageLuminance(of: cgImage) else { return true }
    return luminance >= luminanceThreshold
  }

  private func averageLuminance(of cgImage: CGImage) -> Double? {
    let downscaleWidth = 8
    let downscaleHeight = 8
    let bytesPerRow = downscaleWidth * 4
    var pixels = [UInt8](repeating: 0, count: downscaleWidth * downscaleHeight * 4)

    return pixels.withUnsafeMutableBytes { ptr -> Double? in
      guard let context = CGContext(
        data: ptr.baseAddress,
        width: downscaleWidth,
        height: downscaleHeight,
        bitsPerComponent: 8,
        bytesPerRow: bytesPerRow,
        space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
      ) else { return nil }

      context.interpolationQuality = .medium
      context.draw(cgImage, in: CGRect(x: 0, y: 0, width: downscaleWidth, height: downscaleHeight))

      let buffer = ptr.bindMemory(to: UInt8.self)
      var total: Double = 0
      var count = 0

      for i in stride(from: 0, to: buffer.count, by: 4) {
        let r = Double(buffer[i])
        let g = Double(buffer[i + 1])
        let b = Double(buffer[i + 2])
        let luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
        total += luma
        count += 1
      }

      guard count > 0 else { return nil }
      return total / (Double(count) * 255.0)
    }
  }
}
protocol MethodInvoker: AnyObject { func invoke(name: String, args: [String: Any]) }
