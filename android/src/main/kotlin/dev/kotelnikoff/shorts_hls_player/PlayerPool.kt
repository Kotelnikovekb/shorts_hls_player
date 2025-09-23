package dev.kotelnikoff.shorts_hls_player

import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.util.LinkedHashMap
import kotlin.math.abs

internal class PlayerPool(
    private val context: android.content.Context,
    private val onWatched: ((index: Int, url: String) -> Unit)? = null,
    private val onProgress: ((index: Int, url: String, positionMs: Long, durationMs: Long, bufferedMs: Long) -> Unit)? = null,
    private val onReady: ((index: Int) -> Unit)? = null,
    private val onBuffering: ((index: Int, isBuffering: Boolean) -> Unit)? = null,
) {

    data class Config(
        val maxActivePlayers: Int = 3,
        val progressIntervalMsDefault: Long = 500L
    )
    private val config = Config()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** accessOrder=true — свежие записи в конце */
    private val entries: LinkedHashMap<Int, Entry> = LinkedHashMap(16, 0.75f, true)
    private val surfacesByIndex = mutableMapOf<Int, Surface>()
    /** просто реестр URL'ов, без создания плеера */
    private val urls: MutableMap<Int, String> = mutableMapOf()

    private var activeIndex: Int? = null

    // глобальные настройки
    private var looping: Boolean = false
    private var muted: Boolean = false
    private var volume: Float = 1f
    private var progressEnabled: Boolean = false
    private var progressIntervalMs: Long = config.progressIntervalMsDefault

    // ограничения качества
    private var qualityMaxHeight: Int? = null
    private var qualityMaxWidth: Int? = null
    private var qualityPeakBps: Int? = null

    private fun effectiveVolume(): Float = if (muted) 0f else volume

    private data class Entry(
        val player: ExoPlayer,
        var prepared: Boolean = false,
        var listenerAdded: Boolean = false,
        var reportedForUrl: String? = null,
        var thresholdReported: Boolean = false,
        var progressRunnable: Runnable? = null
    )

    // ---------------- URLs ----------------

    fun setUrl(index: Int, url: String) {
        urls[index] = url
        // если плеер уже создан — просто заменить медиа айтем, сбросить prepared
        entries[index]?.let { e ->
            e.prepared = false
            e.player.setMediaItem(MediaItem.fromUri(url))
            e.reportedForUrl = null
            e.thresholdReported = false
        }
    }

    private fun ensureEntry(index: Int): Entry? {
        val existing = entries[index]
        if (existing != null) return existing
        val url = urls[index] ?: return null

        val player = ExoPlayer.Builder(context).build()
        val e = Entry(player = player)
        entries[index] = e

        ensureListener(index, e)
        applyVolume(e)
        applyLooping(e)
        applyQualityTo(e)
        e.player.setMediaItem(MediaItem.fromUri(url))

        // если Surface уже зарегистрирован — повесим сразу
        surfacesByIndex[index]?.let { e.player.setVideoSurface(it) }

        return e
    }

    fun registerSurface(index: Int, surface: Surface) {
        val old = surfacesByIndex[index]
        if (old != null && old !== surface) {
            runCatching { entries[index]?.player?.clearVideoSurface() }
        }
        surfacesByIndex[index] = surface
        entries[index]?.player?.setVideoSurface(surface)
    }

    // ---------------- PRIME / SWITCH ----------------

    fun prime(index: Int) {
        val e = ensureEntry(index) ?: return
        if (!e.prepared) {
            e.player.prepare()
            e.prepared = true
        }
        enforcePoolBudget(index)
    }

    fun prewarm(next: Int?, prev: Int?) {
        next?.let { if (urls.containsKey(it)) prime(it) }
        prev?.let { if (urls.containsKey(it)) prime(it) }
        activeIndex?.let { enforcePoolBudget(it) }
    }

    fun switchTo(index: Int) {
        val e = ensureEntry(index) ?: return
        if (!e.prepared) prime(index)

        val prevIdx = activeIndex
        val prev = prevIdx?.let { entries[it] }
        if (prev != null && prev !== e) {
            prev.player.pause()
            cancelProgressWatcher(prev)
        }

        applyVolume(e)
        applyLooping(e)
        // если Surface уже есть — просто играем; если нет — Exo начнет играть, как только придёт Surface
        e.player.playWhenReady = true
        e.player.play()
        activeIndex = index

        if (e.player.isPlaying) scheduleProgressWatcher(index, e)

        enforcePoolBudget(index)
    }

    // ---------------- PLAYBACK ----------------

    fun play() { activeIndex?.let { entries[it]?.player?.play() } }

    fun pause() {
        val idx = activeIndex ?: return
        val e = entries[idx] ?: return
        e.player.pause()
        cancelProgressWatcher(e)
    }

    fun togglePlayPause() {
        val e = activeIndex?.let { entries[it] } ?: return
        if (e.player.isPlaying) {
            e.player.pause()
            cancelProgressWatcher(e)
        } else {
            e.player.play()
            activeIndex?.let { scheduleProgressWatcher(it, e) }
        }
    }

    fun isPaused(): Boolean = activeIndex?.let { !entries[it]!!.player.isPlaying } ?: true

    // ---------------- SETTINGS ----------------

    fun setMuted(value: Boolean) {
        muted = value
        applyVolumeToAll()
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        applyVolumeToAll()
    }

    fun setLooping(enabled: Boolean) {
        looping = enabled
        entries.values.forEach { applyLooping(it) }
    }

    fun setProgressTracking(enabled: Boolean, intervalMs: Long?) {
        progressEnabled = enabled
        if (intervalMs != null && intervalMs >= 50L) progressIntervalMs = intervalMs
        if (!progressEnabled) {
            entries.values.forEach { cancelProgressWatcher(it) }
        } else {
            val idx = activeIndex
            if (idx != null) {
                val e = entries[idx]
                if (e != null && e.player.isPlaying) {
                    cancelProgressWatcher(e)
                    scheduleProgressWatcher(idx, e)
                }
            }
        }
    }

    fun setQuality(peakBps: Int?, maxWidth: Int?, maxHeight: Int?) {
        qualityPeakBps = peakBps
        qualityMaxWidth = maxWidth
        qualityMaxHeight = maxHeight
        entries.values.forEach { applyQualityTo(it) }
    }

    // ---------------- INFO ----------------

    fun getPlaybackInfo(index: Int): Map<String, Any?>? {
        val e = entries[index] ?: return null
        val p = e.player
        val vs: VideoSize = p.videoSize
        val pos = p.currentPosition
        val dur = if (p.duration >= 0) p.duration else -1L
        val buf = p.bufferedPosition
        return mapOf(
            "index" to index,
            "prepared" to e.prepared,
            "positionMs" to pos,
            "durationMs" to dur,
            "bufferedMs" to buf,
            "width" to vs.width,
            "height" to vs.height,
            "unappliedRotationDegrees" to vs.unappliedRotationDegrees,
            "pixelWidthHeightRatio" to vs.pixelWidthHeightRatio
        )
    }

    // ---------------- DISPOSE / RELEASE ----------------

    fun disposeIndex(index: Int) {
        val e = entries.remove(index) ?: return
        cancelProgressWatcher(e)
        runCatching { e.player.clearVideoSurface() }
        runCatching { e.player.release() }
        surfacesByIndex.remove(index)?.let { runCatching { it.release() } }
        if (activeIndex == index) activeIndex = null
    }

    fun release() {
        entries.values.forEach { e ->
            cancelProgressWatcher(e)
            runCatching { e.player.clearVideoSurface() }
            runCatching { e.player.release() }
        }
        entries.clear()
        surfacesByIndex.values.forEach { runCatching { it.release() } }
        surfacesByIndex.clear()
        activeIndex = null
    }

    // ---------------- INTERNAL ----------------

    private fun ensureListener(index: Int, e: Entry) {
        if (e.listenerAdded) return
        e.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> onReady?.invoke(index)
                    Player.STATE_ENDED -> {
                        val url = urls[index]
                        if (!url.isNullOrEmpty() && e.reportedForUrl != url) {
                            e.reportedForUrl = url
                            e.thresholdReported = true
                            onWatched?.invoke(index, url)
                        }
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (activeIndex == index) {
                    if (isPlaying) scheduleProgressWatcher(index, e) else cancelProgressWatcher(e)
                }
            }
            override fun onIsLoadingChanged(isLoading: Boolean) {
                onBuffering?.invoke(index, isLoading)
            }
        })
        e.listenerAdded = true
    }

    private fun scheduleProgressWatcher(index: Int, e: Entry) {
        if (!progressEnabled) return
        cancelProgressWatcher(e)
        val r = object : Runnable {
            override fun run() {
                val url = urls[index] ?: return
                val p = e.player
                val st = p.playbackState
                if (st == Player.STATE_READY || st == Player.STATE_BUFFERING) {
                    onProgress?.invoke(
                        index,
                        url,
                        p.currentPosition,
                        if (p.duration >= 0) p.duration else -1L,
                        p.bufferedPosition
                    )
                }
                if (progressEnabled && activeIndex == index && p.isPlaying) {
                    mainHandler.postDelayed(this, progressIntervalMs)
                }
            }
        }
        e.progressRunnable = r
        mainHandler.postDelayed(r, progressIntervalMs)
    }

    private fun cancelProgressWatcher(e: Entry) {
        val r = e.progressRunnable ?: return
        mainHandler.removeCallbacks(r)
        e.progressRunnable = null
    }

    private fun applyVolume(e: Entry) {
        e.player.volume = effectiveVolume()
    }
    private fun applyVolumeToAll() {
        val v = effectiveVolume()
        entries.values.forEach { it.player.volume = v }
    }
    private fun applyLooping(e: Entry) {
        e.player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }
    private fun applyQualityTo(e: Entry) {
        val builder: TrackSelectionParameters.Builder = e.player.trackSelectionParameters.buildUpon()
        val maxW = qualityMaxWidth
        val maxH = qualityMaxHeight
        if (maxW != null || maxH != null) builder.setMaxVideoSize(maxW ?: Int.MAX_VALUE, maxH ?: Int.MAX_VALUE)
        qualityPeakBps?.let { builder.setMaxVideoBitrate(it) }
        // (опционально) эмулятору тяжело с 60fps — можно ограничить до 30:
        // builder.setMaxVideoFrameRate(30)
        e.player.trackSelectionParameters = builder.build()
    }

    /** держим не больше N «живых» плееров; выбрасываем самых дальних от anchorIndex */
    private fun enforcePoolBudget(anchorIndex: Int) {
        val limit = config.maxActivePlayers.coerceAtLeast(1)
        if (entries.size <= limit) return
        val candidates: MutableList<Int> = entries.keys.filter { it != anchorIndex }.toMutableList()
        candidates.sortByDescending { distance(anchorIndex, it) }
        while (entries.size > limit && candidates.isNotEmpty()) {
            val victim = candidates.removeAt(0)
            disposeIndex(victim)
        }
    }

    private fun distance(a: Int, b: Int): Int = abs(a - b)
}