package dev.kotelnikoff.shorts_hls_player

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import dev.kotelnikoff.shorts_hls_player.cache.CacheHolder
import dev.kotelnikoff.shorts_hls_player.cache.Prefetcher
import dev.kotelnikoff.shorts_hls_player.playback.MediaFactories
import java.io.ByteArrayOutputStream
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

internal class PlayerPool(
    private val context: android.content.Context,
    initialConfig: Config = Config(),
    private val onWatched: ((index: Int, url: String) -> Unit)? = null,
    private val onProgress: ((index: Int, url: String, positionMs: Long, durationMs: Long, bufferedMs: Long) -> Unit)? = null,
    private val onReady: ((index: Int) -> Unit)? = null,
    private val onBuffering: ((index: Int, isBuffering: Boolean) -> Unit)? = null,
    private val onFirstFrame: ((index: Int) -> Unit)? = null,
    private val onMetrics: ((index: Int, metrics: MetricsSnapshot) -> Unit)? = null,
) {

    data class Config(
        var maxActivePlayers: Int = 3,
        var progressIntervalMsDefault: Long = 500L,
        var prefetchBytesLimit: Long = 4L * 1024 * 1024
    )
    private val config: Config = initialConfig.copy()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = CacheHolder.obtain(context)
    private val dataSourceFactory = MediaFactories.dataSourceFactory(context, cache)
    private val mediaSourceFactory = MediaFactories.mediaSourceFactory(context, cache)
    private val prefetcher = Prefetcher(cache, dataSourceFactory, config.prefetchBytesLimit)

    /** accessOrder=true — свежие записи в конце */
    private val entries: LinkedHashMap<Int, Entry> = LinkedHashMap(16, 0.75f, true)
    private val surfacesByIndex = mutableMapOf<Int, Surface>()
    /** просто реестр URL'ов, без создания плеера */
    private val urls: MutableMap<Int, String> = mutableMapOf()

    private val thumbnailCache: MutableMap<Int, ByteArray> = ConcurrentHashMap()
    private val thumbnailExecutor: ExecutorService = Executors.newCachedThreadPool()

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
        var progressRunnable: Runnable? = null,
        var firstFrameReported: Boolean = false,
        var isBuffering: Boolean = false,
        val metrics: SessionMetrics = SessionMetrics()
    )

    data class MetricsSnapshot(
        val startupMs: Long?,
        val firstFrameMs: Long?,
        val rebufferCount: Int,
        val rebufferDurationMs: Long,
        val lastRebufferDurationMs: Long?
    )

    private class SessionMetrics {
        private var sessionStart: Long? = null
        private var readyElapsed: Long? = null
        private var firstFrameElapsed: Long? = null
        private var rebufferCount: Int = 0
        private var rebufferDuration: Long = 0
        private var lastRebufferDuration: Long? = null
        private var bufferStart: Long? = null

        fun reset() {
            sessionStart = null
            readyElapsed = null
            firstFrameElapsed = null
            rebufferCount = 0
            rebufferDuration = 0
            lastRebufferDuration = null
            bufferStart = null
        }

        fun start(now: Long) {
            sessionStart = now
            readyElapsed = null
            firstFrameElapsed = null
            rebufferCount = 0
            rebufferDuration = 0
            lastRebufferDuration = null
            bufferStart = null
        }

        fun markReady(now: Long) {
            val start = sessionStart ?: return
            if (readyElapsed == null) readyElapsed = now - start
            bufferStart = null
        }

        fun markFirstFrame(now: Long) {
            val start = sessionStart ?: return
            if (firstFrameElapsed == null) firstFrameElapsed = now - start
            bufferStart = null
        }

        fun markBufferStart(now: Long) {
            if (bufferStart != null) return
            bufferStart = now
            if (readyElapsed != null) rebufferCount += 1
        }

        fun markBufferEnd(now: Long) {
            val start = bufferStart ?: return
            bufferStart = null
            if (readyElapsed != null) {
                val duration = now - start
                rebufferDuration += duration
                lastRebufferDuration = duration
            }
        }

        fun snapshot(): MetricsSnapshot = MetricsSnapshot(
            readyElapsed,
            firstFrameElapsed,
            rebufferCount,
            rebufferDuration,
            lastRebufferDuration
        )
    }

    // ---------------- URLs ----------------

    fun appendUrl(url: String) {
        if (url.isEmpty()) return
        val index = urls.size
        urls[index] = url
    }

    fun replaceUrls(newUrls: List<String>) {
        entries.values.forEach { entry ->
            cancelProgressWatcher(entry)
            runCatching { entry.player.pause() }
            runCatching { entry.player.clearVideoSurface() }
            runCatching { entry.player.release() }
        }
        entries.clear()
        activeIndex = null
        urls.clear()
        thumbnailCache.clear()
        newUrls.forEachIndexed { idx, url ->
            if (url.isNotEmpty()) urls[idx] = url
        }
    }

    private fun ensureEntry(index: Int): Entry? {
        val existing = entries[index]
        if (existing != null) return existing
        val url = urls[index] ?: return null

        val player = ExoPlayer.Builder(context)
            .setLoadControl(createLoadControl())
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        val e = Entry(player = player)
        entries[index] = e

        ensureListener(index, e)
        applyVolume(e)
        applyLooping(e)
        applyQualityTo(e)
        e.player.setMediaItem(MediaItem.fromUri(url))
        prefetcher.prefetch(url)

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
        urls[index]?.let { prefetcher.prefetch(it) }
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
        val url = urls[index]
        if (!e.prepared) prime(index)

        surfacesByIndex[index]?.let { surface ->
            e.player.setVideoSurface(surface)
        }

        val prevIdx = activeIndex
        val prev = prevIdx?.let { entries[it] }
        if (prev != null && prev !== e) {
            prev.player.pause()
            cancelProgressWatcher(prev)
        }

        e.metrics.start(nowMs())
        e.isBuffering = false
        e.firstFrameReported = false
        reportMetrics(index, e.metrics)

        applyVolume(e)
        applyLooping(e)
        url?.let { prefetcher.prefetch(it) }

        e.player.playWhenReady = true
        e.player.play()
        activeIndex = index

        if (e.player.isPlaying) scheduleProgressWatcher(index, e)

        when (e.player.playbackState) {
            Player.STATE_READY -> {
                val alreadyReady = e.metrics.snapshot().startupMs != null
                val now = nowMs()
                e.metrics.markBufferEnd(now)
                e.metrics.markReady(now)
                if (!alreadyReady) onReady?.invoke(index)
                if (e.isBuffering) {
                    e.isBuffering = false
                    onBuffering?.invoke(index, false)
                }
                reportMetrics(index, e.metrics)
            }
            Player.STATE_BUFFERING -> {
                val now = nowMs()
                e.metrics.markBufferStart(now)
                if (!e.isBuffering) {
                    e.isBuffering = true
                    onBuffering?.invoke(index, true)
                }
                reportMetrics(index, e.metrics)
            }
        }

        enforcePoolBudget(index)
    }

    // ---------------- PLAYBACK ----------------

    fun play() { activeIndex?.let { entries[it]?.player?.play() } }
    fun play(index: Int) {
        val e = ensureEntry(index) ?: return
        activeIndex = index
        e.player.playWhenReady = true
        e.player.play()
        scheduleProgressWatcher(index, e)
    }

    fun pause() {
        val idx = activeIndex ?: return
        val e = entries[idx] ?: return
        e.player.pause()
        cancelProgressWatcher(e)
    }

    fun pause(index: Int) {
        val e = entries[index] ?: return
        e.player.pause()
        cancelProgressWatcher(e)
        if (activeIndex == index) {
            activeIndex = null
        }
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
        if (!progressEnabled) {
            entries.values.forEach { cancelProgressWatcher(it) }
            return
        }

        progressIntervalMs = if (intervalMs != null && intervalMs >= 50L) {
            intervalMs
        } else {
            config.progressIntervalMsDefault
        }

        val idx = activeIndex
        if (idx != null) {
            val e = entries[idx]
            if (e != null && e.player.isPlaying) {
                cancelProgressWatcher(e)
                scheduleProgressWatcher(idx, e)
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
        thumbnailCache.remove(index)
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
        thumbnailCache.clear()
        prefetcher.shutdown()
        thumbnailExecutor.shutdownNow()
    }

    // ---------------- INTERNAL ----------------

    private fun ensureListener(index: Int, e: Entry) {
        if (e.listenerAdded) return
        e.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val now = nowMs()
                when (state) {
                    Player.STATE_BUFFERING -> {
                        e.metrics.markBufferStart(now)
                        if (!e.isBuffering) {
                            e.isBuffering = true
                            onBuffering?.invoke(index, true)
                        }
                        reportMetrics(index, e.metrics)
                    }
                    Player.STATE_READY -> {
                        e.prepared = true
                        e.metrics.markBufferEnd(now)
                        e.metrics.markReady(now)
                        if (e.isBuffering) {
                            e.isBuffering = false
                            onBuffering?.invoke(index, false)
                        }
                        onReady?.invoke(index)
                        reportMetrics(index, e.metrics)
                    }
                    Player.STATE_ENDED -> {
                        val url = urls[index]
                        if (!url.isNullOrEmpty() && e.reportedForUrl != url) {
                            e.reportedForUrl = url
                            e.thresholdReported = true
                            onWatched?.invoke(index, url)
                        }
                    }
                    Player.STATE_IDLE -> {
                        if (e.isBuffering) {
                            e.metrics.markBufferEnd(now)
                            e.isBuffering = false
                            onBuffering?.invoke(index, false)
                            reportMetrics(index, e.metrics)
                        }
                    }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (activeIndex == index) {
                    if (isPlaying) scheduleProgressWatcher(index, e) else cancelProgressWatcher(e)
                }
            }
            override fun onRenderedFirstFrame() {
                val now = nowMs()
                e.metrics.markBufferEnd(now)
                e.metrics.markFirstFrame(now)
                if (!e.firstFrameReported) {
                    e.firstFrameReported = true
                    onFirstFrame?.invoke(index)
                }
                reportMetrics(index, e.metrics)
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

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    private fun reportMetrics(index: Int, metrics: SessionMetrics) {
        onMetrics?.invoke(index, metrics.snapshot())
    }

    fun applyConfig(update: Config) {
        val newMax = update.maxActivePlayers.coerceAtLeast(1)
        val newPrefetch = update.prefetchBytesLimit.coerceAtLeast(0L)
        val newDefault = update.progressIntervalMsDefault.coerceAtLeast(50L)
        val previousDefault = config.progressIntervalMsDefault
        val wasUsingDefault = progressIntervalMs == previousDefault
        config.maxActivePlayers = newMax
        config.progressIntervalMsDefault = newDefault
        config.prefetchBytesLimit = newPrefetch
        prefetcher.updateLimit(config.prefetchBytesLimit)
        if (!progressEnabled || wasUsingDefault) {
            progressIntervalMs = config.progressIntervalMsDefault
        }
        val idx = activeIndex
        if (progressEnabled && idx != null) {
            val entry = entries[idx]
            if (entry != null && entry.player.isPlaying) {
                cancelProgressWatcher(entry)
                scheduleProgressWatcher(idx, entry)
            }
        }
        activeIndex?.let { enforcePoolBudget(it) }
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

    fun getThumbnail(index: Int, callback: (ByteArray?) -> Unit) {
        val cached = thumbnailCache[index]
        if (cached != null) {
            mainHandler.post { callback(cached) }
            return
        }
        val url = urls[index]
        if (url.isNullOrEmpty()) {
            mainHandler.post { callback(null) }
            return
        }

        thumbnailExecutor.execute {
            val data = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(url))
                    val attempts = longArrayOf(0L, 300_000L, 600_000L)
                    var bmp: Bitmap? = null
                    for (timeUs in attempts) {
                        bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (bmp != null) break
                        bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        if (bmp != null) break
                    }
                    if (bmp == null) {
                        bmp = retriever.frameAtTime
                    }
                    if (bmp != null) {
                        ByteArrayOutputStream().use { baos ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                            baos.toByteArray()
                        }
                    } else {
                        null
                    }
                } finally {
                    retriever.release()
                }
            }.getOrNull()

            if (data != null) {
                thumbnailCache[index] = data
            }
            mainHandler.post { callback(data) }
        }
    }

    private fun createLoadControl(): LoadControl =
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 6000, 500, 500)
            .setBackBuffer(1500, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
}
