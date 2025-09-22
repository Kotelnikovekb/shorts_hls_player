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

    // прокидываем события наружу
    host.onReady = { [weak self] i in self?.send(["type":"ready","index": i]) }
    host.onBufferingStart = { [weak self] i in self?.send(["type":"bufferingStart","index": i]) }
    host.onBufferingEnd = { [weak self] i in self?.send(["type":"bufferingEnd","index": i]) }
    host.onStall = { [weak self] i in self?.send(["type":"stall","index": i]) }
    host.onProgress = { [weak self] i, pos, dur in
      self?.send(["type":"progress","index": i, "posMs": pos, "durMs": dur as Any])
    }
    host.onError = { [weak self] i, msg in self?.send(["type":"error","index": i, "message": msg]) }

    let e = Entry(url: url, host: host)
    entries[index] = e
    host.prime()
  }

  func dispose(index: Int) {
    entries.removeValue(forKey: index) // deinit host освободит ресурсы
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
}
