import AVFoundation
import UIKit

final class PlayerPool {

  struct Entry {
    let url: URL
    let host: PlayerItemHost
    var player: AVPlayer { host.player }
    var item: AVPlayerItem { host.item }
  }

  private var entries: [Int: Entry] = [:]
  private var urls: [URL] = []
  private var currentIndex: Int = -1
  var onEvent: (([String: Any]) -> Void)?
    
    private var isLooping = false
    private var isMuted = false
    private var volume: Float = 1.0
    
    
    private var progressEnabled = false
    private var progressIntervalMs = 500
    private var watchedFired: Set<Int> = []
    
    weak var methodInvoker: MethodInvoker?


  private let thumbCache = NSCache<NSNumber, NSData>()

  func getThumbnail(index: Int, completion: @escaping (Data?) -> Void) {
      guard index >= 0, index < urls.count else { completion(nil); return }

      // из кэша
      if let cached = thumbCache.object(forKey: NSNumber(value: index)) {
        completion(Data(referencing: cached))
        return
      }

      // берём asset: либо из уже подготовленного host, либо создаём новый AVURLAsset
      let asset: AVURLAsset
      if let e = entries[index] {
        asset = e.host.asset
      } else {
        asset = AVURLAsset(url: urls[index])
      }

      let gen = AVAssetImageGenerator(asset: asset)
      gen.appliesPreferredTrackTransform = true
      // кадр на 0.5s (если нет — 0.0)
      let time = CMTime(seconds: 0.5, preferredTimescale: 600)
      gen.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { [weak self] _, cg, _, _, _ in
        guard let self = self else { completion(nil); return }
        var dataOut: Data?
        if let cg = cg {
          let ui = UIImage(cgImage: cg)
          dataOut = ui.pngData()
          if let d = dataOut {
            self.thumbCache.setObject(d as NSData, forKey: NSNumber(value: index))
          }
        }
        DispatchQueue.main.async { completion(dataOut) }
      }
    }

  func configure() {
    // setCategory — throws, используем try?
    try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [.mixWithOthers])
  }

  func append(urls: [String]) {
    self.urls.append(contentsOf: urls.compactMap { URL(string: $0) })
  }

  func entry(for index: Int) -> Entry? { entries[index] }


    func prime(index: Int) {
      guard index >= 0 && index < urls.count else { return }
      if entries[index] != nil { return }

      let url = urls[index]
      let host = PlayerItemHost(index: index, url: url)

      // apply global flags
      host.player.isMuted = isMuted
      host.player.volume = volume

      // progress observer interval
      host.progressInterval = progressIntervalMs

      // events → EventChannel и/или метод-коллбек
      host.onReady = { [weak self] i in self?.send(["type":"ready","index": i]) }
      host.onBufferingStart = { [weak self] i in self?.send(["type":"bufferingStart","index": i]) }
      host.onBufferingEnd = { [weak self] i in self?.send(["type":"bufferingEnd","index": i]) }
      host.onStall = { [weak self] i in self?.send(["type":"stall","index": i]) }
      host.onError = { [weak self] i, msg in self?.send(["type":"error","index": i, "message": msg]) }

      host.onProgress = { [weak self] idx, posMs, durMs in
        guard let self = self else { return }
        // EventChannel (как было)
        self.send(["type":"progress","index": idx, "posMs": posMs, "durMs": durMs as Any])

        // MethodChannel-коллбек 'onProgress' (с bufferedMs)
        if self.progressEnabled, let e = self.entries[idx] {
          let bufferedMs = Int(self.bufferedDurationMs(for: e.item))
          self.methodInvoker?.invoke(
            name: "onProgress",
            args: ["index": idx, "url": e.url.absoluteString, "positionMs": posMs, "durationMs": durMs ?? -1, "bufferedMs": bufferedMs]
          )
          // 'onWatched' при 95% или завершении
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

      // loop: слушаем конец и переигрываем
      host.onCompleted = { [weak self] idx in
        guard let self = self, self.isLooping, let e = self.entries[idx] else { return }
        e.player.seek(to: .zero)
        e.player.play()
      }

      let entry = Entry(url: url, host: host)
      entries[index] = entry
      host.prime()
    }

  func dispose(index: Int) {
    entries.removeValue(forKey: index) // deinit host освободит ресурсы
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
    private func bufferedDurationMs(for item: AVPlayerItem) -> Double {
        guard let range = item.loadedTimeRanges.first?.timeRangeValue else { return 0 }
        let end = CMTimeGetSeconds(range.start) + CMTimeGetSeconds(range.duration)
        let cur = CMTimeGetSeconds(item.currentTime())
        return max(0, (end - cur) * 1000.0)
      }
}
protocol MethodInvoker: AnyObject { func invoke(name: String, args: [String: Any]) }
