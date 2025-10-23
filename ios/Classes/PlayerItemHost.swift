import Foundation
import AVFoundation
import UIKit

enum QualityPreset: String {
  case auto, p360, p480, p720, p1080, best
}

final class PlayerItemHost {
  let index: Int
  let url: URL
  let asset: AVURLAsset
  let item: AVPlayerItem
  let player: AVPlayer

  // callbacks
  var onReady: ((Int) -> Void)?
  var onBufferingStart: ((Int) -> Void)?
  var onBufferingEnd: ((Int) -> Void)?
  var onStall: ((Int) -> Void)?
  var onProgress: ((Int, Int, Int?) -> Void)? // index, posMs, durMs
  var onError: ((Int, String) -> Void)?
  var onCompleted: ((Int) -> Void)?
  var onFirstFrame: ((Int) -> Void)?
    var progressInterval: Int = 500 { // ms
      didSet {
        if let timeObs = timeObs {
          player.removeTimeObserver(timeObs)
          self.timeObs = nil
        }
        attachProgressObserver()
      }
    }
  var forwardBufferDuration: TimeInterval? {
    didSet { applyForwardBufferDuration() }
  }

  // observers
  private var kvo: [NSKeyValueObservation] = []
  private var timeObs: Any?
  private var notifTokens: [NSObjectProtocol] = []

  private(set) var prepared = false
  private var quality: QualityPreset = .auto
  private var firstFrameReported = false

  init(index: Int, url: URL) {
    self.index = index
    self.url = url
    self.asset = AVURLAsset(url: url)
    self.item = AVPlayerItem(asset: asset)
    self.player = AVPlayer(playerItem: item)
    self.player.automaticallyWaitsToMinimizeStalling = true
    attachObservers()
    applyForwardBufferDuration()
  }

  deinit {
    detachObservers()
    player.replaceCurrentItem(with: nil)
  }

  // MARK: - Public API

  func prime() {
    if item.status == .readyToPlay {
      prepared = true
      onReady?(index)
    }
    firstFrameReported = false
    // иначе дождёмся KVO status == .readyToPlay
  }

  func play() { player.play() }
  func pause() { player.pause() }

  func applyQuality(_ preset: QualityPreset) {
    quality = preset
    guard let ci = player.currentItem else { return }
    switch preset {
    case .auto:
      ci.preferredMaximumResolution = .zero
      ci.preferredPeakBitRate = 0
    case .p360:
      ci.preferredMaximumResolution = CGSize(width: 640, height: 360)
      ci.preferredPeakBitRate = 800_000
    case .p480:
      ci.preferredMaximumResolution = CGSize(width: 854, height: 480)
      ci.preferredPeakBitRate = 1_200_000
    case .p720:
      ci.preferredMaximumResolution = CGSize(width: 1280, height: 720)
      ci.preferredPeakBitRate = 2_500_000
    case .p1080:
      ci.preferredMaximumResolution = CGSize(width: 1920, height: 1080)
      ci.preferredPeakBitRate = 5_000_000
    case .best:
      ci.preferredMaximumResolution = .zero
      ci.preferredPeakBitRate = 0
    }
  }

  func getMeta() -> [String: Any] {
    let durMs: Int? = {
      if let s = secondsIfFinite(item.duration) { return Int(s * 1000) }
      return nil
    }()

    let size = item.presentationSize
    var fps: Double?
    if let track = asset.tracks(withMediaType: .video).first {
      let rate = track.nominalFrameRate
      if rate > 0 { fps = Double(rate) }
    }

    return [
      "durationMs": durMs as Any,
      "width": Int(size.width),
      "height": Int(size.height),
      "fps": fps as Any
    ]
  }

  func getPlaybackInfo() -> [String: Any?] {
    let duration = secondsIfFinite(item.duration)
    let position = CMTimeGetSeconds(player.currentTime())
    let size = item.presentationSize
    return [
      "index": index,
      "prepared": prepared,
      "positionMs": Int(position * 1000),
      "durationMs": duration.map { Int($0 * 1000) } as Any,
      "width": Int(size.width),
      "height": Int(size.height)
    ]
  }

  func getVariants() -> [[String: Any]] {
      var out: [[String: Any]] = []
      if #available(iOS 15.0, *) {
        for v in asset.variants {
          // одиночный объект, НЕ массив
          let va = v.videoAttributes
          let size = va?.presentationSize ?? .zero
          let h = size.height > 0 ? Int(size.height) : nil
          let w = size.width  > 0 ? Int(size.width)  : nil

          var br: Int?
          if let avg = v.averageBitRate, avg > 0 {
            br = Int(avg)
          }

          let label = (h != nil) ? "\(h!)p" : "Auto"
          out.append([
            "height": h as Any,
            "width":  w as Any,
            "bitrate": br as Any,
            "label": label
          ])
        }
        if out.isEmpty { out.append(["label": "Auto"]) }
      } else {
        out.append(["label": "Auto"])
      }
      return out
    }

  // MARK: - Observers

  private func attachObservers() {
    // status
    let obsStatus = item.observe(\.status, options: [.new]) { [weak self] it, _ in
      guard let self = self else { return }
      switch it.status {
      case .readyToPlay:
        self.prepared = true
        self.onReady?(self.index)
      case .failed:
        let msg = it.error?.localizedDescription ?? "AVPlayerItem failed"
        self.onError?(self.index, msg)
      default:
        break
      }
    }

    // buffering
    let obsEmpty = item.observe(\.isPlaybackBufferEmpty, options: [.new]) { [weak self] it, _ in
      guard let self = self else { return }
      if it.isPlaybackBufferEmpty {
        self.onBufferingStart?(self.index)
      }
    }
    let obsKeepUp = item.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] it, _ in
      guard let self = self else { return }
      if it.isPlaybackLikelyToKeepUp {
        self.onBufferingEnd?(self.index)
        if !self.firstFrameReported && self.item.status == .readyToPlay {
          self.firstFrameReported = true
          self.onFirstFrame?(self.index)
        }
      }
    }

    // stall
    let tokStall = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemPlaybackStalled, object: item, queue: .main
    ) { [weak self] _ in
      guard let self = self else { return }
      self.onStall?(self.index)
    }

      let tokEnd = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
          ) { [weak self] _ in
            guard let self = self else { return }
            self.onCompleted?(self.index)
          }
          notifTokens.append(tokEnd)

          attachProgressObserver()

    let obsTimeControl = player.observe(\.timeControlStatus, options: [.new]) { [weak self] pl, _ in
      guard let self = self else { return }
      if !self.firstFrameReported && pl.timeControlStatus == .playing {
        self.firstFrameReported = true
        self.onFirstFrame?(self.index)
      }
    }

    let obsPresentationSize = item.observe(\.presentationSize, options: [.new]) { [weak self] it, _ in
      guard let self = self else { return }
      if !self.firstFrameReported && it.presentationSize.width > 0 && it.presentationSize.height > 0 {
        self.firstFrameReported = true
        self.onFirstFrame?(self.index)
      }
    }

    kvo = [obsStatus, obsEmpty, obsKeepUp, obsTimeControl, obsPresentationSize]
    notifTokens = [tokStall]
  }

  private func detachObservers() {
    kvo.forEach { $0.invalidate() }
    kvo.removeAll()
    if let timeObs = timeObs {
      player.removeTimeObserver(timeObs)
      self.timeObs = nil
    }
    notifTokens.forEach { NotificationCenter.default.removeObserver($0) }
    notifTokens.removeAll()
  }
    private func attachProgressObserver() {
      let timescale: Int32 = 1000
      let interval = CMTimeMake(value: Int64(progressInterval), timescale: timescale)
      timeObs = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] t in
        guard let self = self else { return }
        let pos = Int(CMTimeGetSeconds(t) * 1000)
        let dur: Int? = {
          if let s = self.secondsIfFinite(self.item.duration) { return Int(s * 1000) }
          return nil
        }()
        self.onProgress?(self.index, pos, dur)
      }
    }

  // MARK: - Helpers

  private func secondsIfFinite(_ t: CMTime) -> Double? {
    if t.isValid && !t.isIndefinite && !t.isNegativeInfinity && !t.isPositiveInfinity {
      return CMTimeGetSeconds(t)
    }
    return nil
  }

  private func applyForwardBufferDuration() {
    if let seconds = forwardBufferDuration {
      item.preferredForwardBufferDuration = seconds
    } else {
      item.preferredForwardBufferDuration = 0
    }
  }
}
