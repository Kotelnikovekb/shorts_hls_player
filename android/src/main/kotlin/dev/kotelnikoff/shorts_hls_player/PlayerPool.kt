package dev.kotelnikoff.shorts_hls_player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import dev.kotelnikoff.shorts_hls_player.cache.CacheHolder
import dev.kotelnikoff.shorts_hls_player.cache.Prefetcher
import dev.kotelnikoff.shorts_hls_player.playback.MediaFactories
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

internal class PlayerPool(
    private val context: Context,
    initialConfig: Config = Config(),
    private val onWatched: ((index: Int, url: String) -> Unit)? = null,
    private val onProgress: ((index: Int, url: String, positionMs: Long, durationMs: Long, bufferedMs: Long) -> Unit)? = null,
    private val onReady: ((index: Int) -> Unit)? = null,
    private val onBuffering: ((index: Int, isBuffering: Boolean) -> Unit)? = null,
    private val onFirstFrame: ((index: Int) -> Unit)? = null,
    private val onMetrics: ((index: Int, metrics: MetricsSnapshot) -> Unit)? = null,
    private val onVideoSizeChanged: ((index: Int, width: Int, height: Int) -> Unit)? = null,
) {

    data class Config(
        var maxActivePlayers: Int = 5,
        var progressIntervalMsDefault: Long = 200L,
        var prefetchBytesLimit: Long = 16L * 1024 * 1024
    )

    data class MetricsSnapshot(
        val startupMs: Long?,
        val firstFrameMs: Long?,
        val rebufferCount: Int,
        val rebufferDurationMs: Long,
        val lastRebufferDurationMs: Long?
    )

    private data class PlayerEntry(
        val index: Int,
        val url: String,
        val player: ExoPlayer,
        var prepared: Boolean = false,
        var readyReported: Boolean = false,
        var firstFrameReported: Boolean = false,
        var listener: Player.Listener? = null,
        var progressRunnable: Runnable? = null,
        val metrics: SessionMetrics = SessionMetrics()
    )

    private class SessionMetrics {
        private var sessionStart: Long? = null
        private var readyElapsed: Long? = null
        private var firstFrameElapsed: Long? = null
        private var rebufferCount: Int = 0
        private var rebufferDuration: Long = 0
        private var lastRebufferDuration: Long? = null
        private var bufferStart: Long? = null

        fun reset(now: Long) {
            sessionStart = now
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
            val start = sessionStart ?: now
            if (readyElapsed == null) readyElapsed = now - start
            bufferStart = null
        }

        fun markFirstFrame(now: Long) {
            val start = sessionStart ?: now
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
            startupMs = readyElapsed,
            firstFrameMs = firstFrameElapsed,
            rebufferCount = rebufferCount,
            rebufferDurationMs = rebufferDuration,
            lastRebufferDurationMs = lastRebufferDuration
        )
    }

    /**
     * Простая LRU с контролем количества элементов и совокупного размера.
     */
    private class ThumbnailCache(
        private val maxEntries: Int,
        private val maxBytes: Int
    ) : LinkedHashMap<Int, ByteArray>(16, 0.75f, true) {
        private var currentBytes: Int = 0

        override fun put(key: Int, value: ByteArray): ByteArray? {
            val prev = super.put(key, value)
            if (prev != null) currentBytes -= prev.size
            currentBytes += value.size
            trim()
            return prev
        }

        override fun remove(key: Int): ByteArray? {
            val removed = super.remove(key)
            if (removed != null) currentBytes -= removed.size
            return removed
        }

        override fun clear() {
            super.clear()
            currentBytes = 0
        }

        private fun trim() {
            val iterator = entries.iterator()
            while ((size > maxEntries || currentBytes > maxBytes) && iterator.hasNext()) {
                val entry = iterator.next()
                currentBytes -= entry.value.size
                iterator.remove()
            }
        }
    }

    companion object {
        private const val TAG = "ShortsPlayerPool"
        private const val WATCHED_THRESHOLD = 0.95
        private const val THUMBNAIL_PREFETCH_COUNT = 3
        private const val THUMBNAIL_CACHE_ENTRIES = 32
        private const val THUMBNAIL_CACHE_BYTES = 32 * 1024 * 1024
        private const val LOW_MEMORY_THRESHOLD_MB = 50L

        private val PREVIEW_ATTEMPTS_US = longArrayOf(
            0L,
            120_000L,
            300_000L,
            600_000L,
            1_000_000L,
            1_600_000L,
        )
        private const val LUMA_THRESHOLD = 0.035f
        private const val MAX_LUMA_SAMPLES = 400
    }

    private val config: Config = initialConfig.copy()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = CacheHolder.obtain(context)
    private val dataSourceFactory = MediaFactories.dataSourceFactory(context, cache)
    private val mediaSourceFactory = MediaFactories.mediaSourceFactory(context, cache)
    private val prefetcher = Prefetcher(cache, dataSourceFactory, config.prefetchBytesLimit)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val urlList = mutableListOf<String>()
    private val entries = mutableMapOf<Int, PlayerEntry>()
    private val surfaces = mutableMapOf<Int, Surface>()
    private val watchedOnce = mutableSetOf<Int>()
    private val thumbnailCache = ThumbnailCache(
        maxEntries = THUMBNAIL_CACHE_ENTRIES,
        maxBytes = THUMBNAIL_CACHE_BYTES
    )
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile private var activeIndex: Int? = null
    @Volatile private var looping: Boolean = false
    @Volatile private var muted: Boolean = false
    @Volatile private var volume: Float = 1f
    @Volatile private var progressEnabled: Boolean = false
    @Volatile private var progressIntervalMs: Long = config.progressIntervalMsDefault
    @Volatile private var qualityMaxHeight: Int? = null
    @Volatile private var qualityMaxWidth: Int? = null
    @Volatile private var qualityPeakBps: Int? = null
    @Volatile private var isAppInForeground: Boolean = true
    @Volatile private var resumeAfterBackground: Boolean = false

    private var switchCount = 0
    private var lastSwitchTime = 0L
    private var averageSwitchTime = 0L

    fun appendUrl(url: String) {
        ensureMainThread("appendUrl")
        if (url.isBlank()) return
        val index = urlList.size
        urlList += url
        if (index < THUMBNAIL_PREFETCH_COUNT) {
            getThumbnail(index) { _ -> }
        }
    }

    fun replaceUrls(newUrls: List<String>) {
        ensureMainThread("replaceUrls")
        releaseEntries()
        urlList.clear()
        urlList.addAll(newUrls.filter { it.isNotBlank() })
        activeIndex = null
        watchedOnce.clear()
        thumbnailCache.clear()
        if (urlList.isNotEmpty()) {
            prewarmFirstElements()
        }
    }

    fun registerSurface(index: Int, surface: Surface) {
        ensureMainThread("registerSurface")
        if (!surface.isValid) {
            Log.w(TAG, "registerSurface: invalid surface for index=$index")
            return
        }
        surfaces[index]?.let { existing ->
            if (existing !== surface) {
                entries[index]?.player?.clearVideoSurface()
            }
        }
        surfaces[index] = surface
        
        // Отложить отрисовку thumbnail, чтобы дать Surface время инициализироваться
        mainHandler.post {
            if (surface.isValid) {
                drawThumbnailIntoSurface(index, surface)
            } else {
                Log.w(TAG, "registerSurface: surface became invalid after registration for index=$index")
            }
        }
        
        entries[index]?.let { entry ->
            attachSurface(entry, surface)
            if (!entry.prepared) {
                prime(index)
            }
        }
    }

    fun prime(index: Int) {
        ensureMainThread("prime")
        val entry = ensureEntry(index) ?: return
        if (!entry.prepared) {
            try {
                entry.player.prepare()
                entry.prepared = true
            } catch (t: Throwable) {
                Log.e(TAG, "prime: failed to prepare player for index=$index", t)
                disposeIndex(index)
                return
            }
        }
        enforceBudget(activeIndex ?: index)
    }

    fun prewarm(next: Int?, prev: Int?) {
        ensureMainThread("prewarm")
        next?.takeIf { urlAt(it) != null }?.let { prime(it) }
        prev?.takeIf { urlAt(it) != null }?.let { prime(it) }
        activeIndex?.let { anchor ->
            val forward = anchor + 1
            val backward = anchor - 1
            if (urlAt(forward) != null) prime(forward)
            if (urlAt(backward) != null) prime(backward)
            enforceBudget(anchor)
        }
    }

    fun prewarmFirstElements() {
        ensureMainThread("prewarmFirstElements")
        val count = minOf(THUMBNAIL_PREFETCH_COUNT, urlList.size)
        for (i in 0 until count) {
            prime(i)
        }
    }

    fun switchTo(index: Int) {
        ensureMainThread("switchTo")
        val start = nowMs()
        val entry = ensureEntry(index) ?: return
        val url = entry.url
        val previousIndex = activeIndex
        if (previousIndex != null && previousIndex != index) {
            entries[previousIndex]?.let { stopPlayback(it) }
        }

        if (!entry.prepared) {
            try {
                entry.player.prepare()
                entry.prepared = true
            } catch (t: Throwable) {
                Log.e(TAG, "switchTo: failed to prepare player for index=$index", t)
                return
            }
        } else {
            entry.player.seekTo(0)
        }
        watchedOnce.remove(index)

        activeIndex = index

        val surface = surfaces[index]?.takeIf { it.isValid }
        if (surface != null) {
            attachSurface(entry, surface)
        } else {
            surfaces.remove(index)
            Log.w(TAG, "switchTo: surface missing for index=$index")
        }

        requestAudioFocus()
        startPlayback(entry)
        prefetcher.prefetch(url)
        prewarm(index + 1, index - 1)
        enforceBudget(index)

        val switchTime = nowMs() - start
        switchCount += 1
        lastSwitchTime = switchTime
        averageSwitchTime = if (switchCount == 0) switchTime else
            (averageSwitchTime * (switchCount - 1) + switchTime) / switchCount

        if (switchTime > 120) {
            Log.w(TAG, "switchTo: slow switch ${switchTime}ms for index=$index")
        }
    }

    fun play() {
        ensureMainThread("play")
        val idx = activeIndex ?: return
        entries[idx]?.let { startPlayback(it) }
    }

    fun play(index: Int) {
        ensureMainThread("play(index)")
        switchTo(index)
    }

    fun pause() {
        ensureMainThread("pause")
        abandonAudioFocus()
        val idx = activeIndex ?: return
        entries[idx]?.let { stopPlayback(it) }
    }

    fun pause(index: Int) {
        ensureMainThread("pause(index)")
        if (activeIndex == index) abandonAudioFocus()
        entries[index]?.let { stopPlayback(it) }
    }

    fun togglePlayPause() {
        ensureMainThread("togglePlayPause")
        val idx = activeIndex ?: return
        val entry = entries[idx] ?: return
        if (entry.player.isPlaying) {
            stopPlayback(entry)
        } else {
            startPlayback(entry)
        }
    }

    fun isPaused(): Boolean {
        ensureMainThread("isPaused")
        val idx = activeIndex ?: return true
        val entry = entries[idx] ?: return true
        return !entry.player.isPlaying
    }

    fun setMuted(value: Boolean) {
        ensureMainThread("setMuted")
        muted = value
        entries.values.forEach { applyVolume(it) }
    }

    fun setVolume(v: Float) {
        ensureMainThread("setVolume")
        volume = v.coerceIn(0f, 1f)
        entries.values.forEach { applyVolume(it) }
    }

    fun setLooping(enabled: Boolean) {
        ensureMainThread("setLooping")
        looping = enabled
        entries.values.forEach { applyLooping(it) }
    }

    fun setProgressTracking(enabled: Boolean, intervalMs: Long?) {
        ensureMainThread("setProgressTracking")
        progressEnabled = enabled
        if (!progressEnabled) {
            entries.values.forEach { cancelProgressWatcher(it) }
            watchedOnce.clear()
            return
        }
        progressIntervalMs = intervalMs?.takeIf { it >= 50L } ?: config.progressIntervalMsDefault
        val idx = activeIndex
        if (idx != null) {
            entries[idx]?.let {
                cancelProgressWatcher(it)
                if (it.player.isPlaying) scheduleProgressWatcher(it)
            }
        }
    }

    fun setQuality(peakBps: Int?, maxWidth: Int?, maxHeight: Int?) {
        ensureMainThread("setQuality")
        qualityPeakBps = peakBps
        qualityMaxWidth = maxWidth
        qualityMaxHeight = maxHeight
        entries.values.forEach { applyQuality(it) }
    }

    fun applyConfig(update: Config) {
        ensureMainThread("applyConfig")
        val newMax = update.maxActivePlayers.coerceAtLeast(1)
        val newDefault = update.progressIntervalMsDefault.coerceAtLeast(50L)
        val newPrefetch = update.prefetchBytesLimit.coerceAtLeast(0L)

        val previousDefault = config.progressIntervalMsDefault
        val usedDefault = progressIntervalMs == previousDefault

        config.maxActivePlayers = newMax
        config.progressIntervalMsDefault = newDefault
        config.prefetchBytesLimit = newPrefetch

        if (!progressEnabled || usedDefault) {
            progressIntervalMs = config.progressIntervalMsDefault
        }
        prefetcher.updateLimit(config.prefetchBytesLimit)

        activeIndex?.let { idx ->
            entries[idx]?.let { entry ->
                if (progressEnabled && entry.player.isPlaying) {
                    cancelProgressWatcher(entry)
                    scheduleProgressWatcher(entry)
                }
            }
            enforceBudget(idx)
        }
    }

    fun getPlaybackInfo(index: Int): Map<String, Any?>? {
        ensureMainThread("getPlaybackInfo")
        val entry = entries[index] ?: return null
        val player = entry.player
        val size: VideoSize = player.videoSize
        val duration = if (player.duration >= 0) player.duration else -1L
        val buffered = player.bufferedPosition
        return mapOf(
            "index" to index,
            "prepared" to entry.prepared,
            "positionMs" to player.currentPosition,
            "durationMs" to duration,
            "bufferedMs" to buffered,
            "width" to size.width,
            "height" to size.height,
            "unappliedRotationDegrees" to size.unappliedRotationDegrees,
            "pixelWidthHeightRatio" to size.pixelWidthHeightRatio
        )
    }

    fun getPerformanceMetrics(): Map<String, Any> {
        ensureMainThread("getPerformanceMetrics")
        val availableMemory = getAvailableMemoryMB()
        return mapOf(
            "switchCount" to switchCount,
            "lastSwitchTime" to lastSwitchTime,
            "averageSwitchTime" to averageSwitchTime,
            "activePlayers" to entries.size,
            "thumbnailCacheSize" to thumbnailCache.size,
            "availableMemoryMB" to availableMemory,
            "isLowMemory" to (availableMemory < LOW_MEMORY_THRESHOLD_MB)
        )
    }

    fun forceSurfaceRefresh(index: Int) {
        ensureMainThread("forceSurfaceRefresh")
        val entry = entries[index] ?: return
        val surface = surfaces[index]?.takeIf { it.isValid } ?: return
        attachSurface(entry, surface)
    }

    fun onAppPaused() {
        ensureMainThread("onAppPaused")
        isAppInForeground = false
        resumeAfterBackground = entries[activeIndex]?.player?.isPlaying == true
        if (resumeAfterBackground) {
            pause()
        }
    }

    fun onAppResumed() {
        ensureMainThread("onAppResumed")
        isAppInForeground = true
        if (resumeAfterBackground) {
            resumeAfterBackground = false
            play()
        }
    }

    fun disposeIndex(index: Int) {
        ensureMainThread("disposeIndex")
        val entry = entries.remove(index) ?: return
        cancelProgressWatcher(entry)
        try {
            entry.listener?.let { entry.player.removeListener(it) }
        } catch (_: Throwable) {
        }
        runCatching { entry.player.stop() }
        runCatching { entry.player.clearVideoSurface() }
        runCatching { entry.player.release() }
        if (activeIndex == index) activeIndex = null
        watchedOnce.remove(index)
    }

    fun release() {
        ensureMainThread("release")
        abandonAudioFocus()
        releaseEntries()
        surfaces.clear()
        activeIndex = null
        watchedOnce.clear()
        thumbnailCache.clear()

        runCatching { prefetcher.shutdown() }
        runCatching { thumbnailExecutor.shutdownNow() }
    }

    fun getThumbnail(index: Int, callback: (ByteArray?) -> Unit) {
        ensureMainThread("getThumbnail")
        val cached = thumbnailCache[index]
        if (cached != null) {
            mainHandler.post { callback(cached) }
            return
        }
        val url = urlAt(index)
        if (url == null) {
            mainHandler.post { callback(null) }
            return
        }
        thumbnailExecutor.execute {
            val data = loadThumbnail(index, url)
            if (data != null) {
                synchronized(thumbnailCache) { thumbnailCache[index] = data }
                mainHandler.post {
                    surfaces[index]?.takeIf { it.isValid }?.let { drawThumbnailIntoSurface(index, it) }
                    callback(data)
                }
            } else {
                mainHandler.post { callback(null) }
            }
        }
    }

    private fun ensureEntry(index: Int): PlayerEntry? {
        val existing = entries[index]
        if (existing != null) return existing
        val url = urlAt(index)
        if (url.isNullOrBlank()) {
            Log.w(TAG, "ensureEntry: no url for index=$index")
            return null
        }
        
        Log.d(TAG, "Creating ExoPlayer for index=$index, url=$url")
        
        val player = try {
            // Добавляем таймаут для создания ExoPlayer
            val startTime = System.currentTimeMillis()
            val playerBuilder = ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(createLoadControl())
            
            val builtPlayer = playerBuilder.build()
            val buildTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "ExoPlayer created in ${buildTime}ms for index=$index")
            builtPlayer
        } catch (t: Throwable) {
            Log.e(TAG, "ensureEntry: failed to create ExoPlayer for index=$index", t)
            return null
        }
        
        try {
            player.videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.volume = if (muted) 0f else volume
            player.playWhenReady = false
            
            Log.d(TAG, "Setting media item for index=$index")
            player.setMediaItem(MediaItem.fromUri(url))
            
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .applyQuality()
                .build()

            val entry = PlayerEntry(index = index, url = url, player = player)
            entry.listener = createListener(entry)
            player.addListener(entry.listener!!)
            entries[index] = entry
            entry.metrics.reset(nowMs())
            applyLooping(entry)
            applyVolume(entry)
            applyQuality(entry)
            prefetcher.prefetch(url)

            surfaces[index]?.takeIf { it.isValid }?.let { attachSurface(entry, it) }
            
            Log.d(TAG, "Player entry created successfully for index=$index")
            return entry
        } catch (t: Throwable) {
            Log.e(TAG, "ensureEntry: failed to configure player for index=$index", t)
            runCatching { player.release() }
            return null
        }
    }

    private fun createListener(entry: PlayerEntry): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        entry.metrics.markBufferEnd(nowMs())
                        entry.metrics.markReady(nowMs())
                        onBuffering?.invoke(entry.index, false)
                        if (!entry.readyReported) {
                            entry.readyReported = true
                            onReady?.invoke(entry.index)
                        }
                        captureSnapshotForCache(entry)
                        reportMetrics(entry.index, entry.metrics)
                    }
                    Player.STATE_BUFFERING -> {
                        entry.metrics.markBufferStart(nowMs())
                        onBuffering?.invoke(entry.index, true)
                        reportMetrics(entry.index, entry.metrics)
                    }
                    Player.STATE_ENDED -> {
                        entry.metrics.markBufferEnd(nowMs())
                        if (looping) {
                            entry.player.seekTo(0)
                            entry.player.playWhenReady = true
                            entry.player.play()
                        } else {
                            cancelProgressWatcher(entry)
                        }
                    }
                    Player.STATE_IDLE -> {
                        cancelProgressWatcher(entry)
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                entry.metrics.markFirstFrame(nowMs())
                if (!entry.firstFrameReported) {
                    entry.firstFrameReported = true
                    onFirstFrame?.invoke(entry.index)
                }
                reportMetrics(entry.index, entry.metrics)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                onVideoSizeChanged?.invoke(entry.index, videoSize.width, videoSize.height)
                if (videoSize.width > 0 && videoSize.height > 0) {
                    surfaces[entry.index]?.takeIf { it.isValid }?.let { surface ->
                        // Отложить отрисовку thumbnail, чтобы избежать конфликтов с изменением размера видео
                        mainHandler.post {
                            if (surface.isValid) {
                                drawThumbnailIntoSurface(entry.index, surface)
                            }
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error index=${entry.index}", error)
                onBuffering?.invoke(entry.index, false)
                reportMetrics(entry.index, entry.metrics)
            }
        }
    }

    private fun attachSurface(entry: PlayerEntry, surface: Surface) {
        if (!surface.isValid) return
        runCatching {
            entry.player.clearVideoSurface()
            entry.player.setVideoSurface(surface)
        }.onFailure { t ->
            Log.e(TAG, "attachSurface: failed for index=${entry.index}", t)
        }
    }

    private fun startPlayback(entry: PlayerEntry) {
        if (!isAppInForeground) {
            resumeAfterBackground = true
            return
        }
        try {
            val now = nowMs()
            if (entry.player.currentPosition <= 0L || !entry.readyReported) {
                entry.metrics.start(now)
            } else {
                entry.metrics.markBufferEnd(now)
            }
            entry.player.playWhenReady = true
            entry.player.play()
            if (progressEnabled) {
                cancelProgressWatcher(entry)
                scheduleProgressWatcher(entry)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startPlayback: failed for index=${entry.index}", t)
        }
    }

    private fun stopPlayback(entry: PlayerEntry) {
        cancelProgressWatcher(entry)
        runCatching { entry.player.pause() }
        entry.player.playWhenReady = false
        onBuffering?.invoke(entry.index, false)
    }

    private fun scheduleProgressWatcher(entry: PlayerEntry) {
        if (!progressEnabled) return
        val runnable = object : Runnable {
            override fun run() {
                val player = entry.player
                if (!player.isPlaying) {
                    entry.progressRunnable = null
                    return
                }
                val position = player.currentPosition.coerceAtLeast(0L)
                val duration = if (player.duration > 0) player.duration else -1L
                val buffered = player.bufferedPosition
                val url = entry.url
                onProgress?.invoke(entry.index, url, position, duration, buffered)

                if (duration > 0) {
                    val fraction = position.toDouble() / duration.toDouble()
                    if (fraction >= WATCHED_THRESHOLD && watchedOnce.add(entry.index)) {
                        onWatched?.invoke(entry.index, url)
                    }
                }

                entry.progressRunnable = this
                mainHandler.postDelayed(this, progressIntervalMs)
            }
        }
        entry.progressRunnable = runnable
        mainHandler.postDelayed(runnable, progressIntervalMs)
    }

    private fun cancelProgressWatcher(entry: PlayerEntry) {
        val runnable = entry.progressRunnable ?: return
        entry.progressRunnable = null
        mainHandler.removeCallbacks(runnable)
    }

    private fun applyVolume(entry: PlayerEntry) {
        entry.player.volume = if (muted) 0f else volume
    }

    private fun applyLooping(entry: PlayerEntry) {
        entry.player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private fun TrackSelectionParameters.Builder.applyQuality(): TrackSelectionParameters.Builder {
        val maxWidth = qualityMaxWidth
        val maxHeight = qualityMaxHeight
        if (maxWidth != null || maxHeight != null) {
            setMaxVideoSize(maxWidth ?: Int.MAX_VALUE, maxHeight ?: Int.MAX_VALUE)
        }
        qualityPeakBps?.let { setMaxVideoBitrate(it) }
        return this
    }

    private fun applyQuality(entry: PlayerEntry) {
        entry.player.trackSelectionParameters = entry.player.trackSelectionParameters
            .buildUpon()
            .applyQuality()
            .build()
    }

    private fun loadThumbnail(index: Int, url: String): ByteArray? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, android.net.Uri.parse(url))
                extractMeaningfulThumbnail(index, retriever)
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    private fun extractMeaningfulThumbnail(index: Int, retriever: MediaMetadataRetriever): ByteArray? {
        for (timeUs in PREVIEW_ATTEMPTS_US) {
            val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: continue
            val luminance = averageLuminance(bmp)
            if (luminance >= LUMA_THRESHOLD) {
                val bytes = compressBitmap(bmp)
                bmp.recycle()
                if (bytes != null) {
                    Log.d(TAG, "Thumbnail index=$index timeUs=$timeUs luma=${formatLuminance(luminance)}")
                    return bytes
                }
            } else {
                bmp.recycle()
            }
        }
        val fallback = retriever.frameAtTime ?: return null
        val luminance = averageLuminance(fallback)
        val bytes = compressBitmap(fallback)
        fallback.recycle()
        if (bytes != null) {
            Log.d(TAG, "Thumbnail fallback index=$index luma=${formatLuminance(luminance)}")
        }
        return bytes
    }

    private fun compressBitmap(bmp: Bitmap): ByteArray? {
        return runCatching {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            ByteArrayOutputStream().use { baos ->
                bmp.compress(format, 90, baos)
                baos.toByteArray()
            }
        }.getOrNull()
    }

    private fun averageLuminance(bmp: Bitmap): Float {
        val width = bmp.width
        val height = bmp.height
        if (width <= 0 || height <= 0) return 0f
        val stepX = max(1, width / 24)
        val stepY = max(1, height / 24)
        var samples = 0
        var total = 0f
        var y = 0
        while (y < height && samples < MAX_LUMA_SAMPLES) {
            var x = 0
            while (x < width && samples < MAX_LUMA_SAMPLES) {
                val color = bmp.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                total += 0.2126f * r + 0.7152f * g + 0.0722f * b
                samples += 1
                x += stepX
            }
            y += stepY
        }
        return if (samples == 0) 0f else total / (samples * 255f)
    }

    private fun formatLuminance(value: Float): String = String.format(Locale.US, "%.3f", value)

    private fun drawThumbnailIntoSurface(index: Int, surface: Surface) {
        if (!surface.isValid) {
            Log.w(TAG, "drawThumbnailIntoSurface: surface is invalid for index=$index")
            return
        }
        val bytes = thumbnailCache[index] ?: return
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        
        try {
            // Дополнительная проверка состояния Surface перед блокировкой Canvas
            if (!surface.isValid) {
                Log.w(TAG, "drawThumbnailIntoSurface: surface became invalid before lock for index=$index")
                bmp.recycle()
                return
            }
            
            val canvas = surface.lockCanvas(null)
            if (canvas == null) {
                Log.w(TAG, "drawThumbnailIntoSurface: failed to lock canvas for index=$index")
                bmp.recycle()
                return
            }
            
            try {
                canvas.drawColor(Color.BLACK)
                val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
                val src = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
                val matrix = Matrix()
                val scale = max(dst.width() / src.width(), dst.height() / src.height())
                val scaledW = src.width() * scale
                val scaledH = src.height() * scale
                val dx = (dst.width() - scaledW) / 2f
                val dy = (dst.height() - scaledH) / 2f
                matrix.postScale(scale, scale)
                matrix.postTranslate(dx, dy)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(bmp, matrix, paint)
            } finally {
                try {
                    surface.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    Log.w(TAG, "drawThumbnailIntoSurface: failed to unlock canvas for index=$index", e)
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "drawThumbnailIntoSurface: IllegalArgumentException for index=$index - surface may be in wrong state", e)
        } catch (e: Exception) {
            Log.w(TAG, "drawThumbnailIntoSurface: unexpected error for index=$index", e)
        } finally {
            bmp.recycle()
        }
    }

    private fun captureSnapshotForCache(entry: PlayerEntry) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (thumbnailCache.containsKey(entry.index)) return
        val surface = surfaces[entry.index]?.takeIf { it.isValid } ?: return
        val videoSize = entry.player.videoSize
        val width = if (videoSize.width > 0) videoSize.width else 360
        val height = if (videoSize.height > 0) videoSize.height else 640
        if (width <= 0 || height <= 0) return
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    thumbnailExecutor.execute {
                        val bytes = compressBitmap(bitmap)
                        if (bytes != null) {
                            synchronized(thumbnailCache) { thumbnailCache[entry.index] = bytes }
                        }
                        bitmap.recycle()
                    }
                } else {
                    bitmap.recycle()
                }
            }, mainHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "captureSnapshotForCache: failed for index=${entry.index}", t)
            bitmap.recycle()
        }
    }

    private fun reportMetrics(index: Int, metrics: SessionMetrics) {
        onMetrics?.invoke(index, metrics.snapshot())
    }

    private fun getAvailableMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val available = maxMemory - (totalMemory - freeMemory)
        val availableMB = available / (1024 * 1024)
        if (availableMB < LOW_MEMORY_THRESHOLD_MB / 2) {
            thumbnailCache.clear()
            System.gc()
        }
        return availableMB
    }

    private fun enforceBudget(anchorIndex: Int) {
        if (entries.size <= config.maxActivePlayers) return
        val victims = entries.keys
            .filter { it != anchorIndex }
            .sortedByDescending { abs(it - anchorIndex) }
        var i = 0
        while (entries.size > config.maxActivePlayers && i < victims.size) {
            val victim = victims[i]
            disposeIndex(victim)
            i += 1
        }
    }

    private fun releaseEntries() {
        entries.values.forEach { entry ->
            cancelProgressWatcher(entry)
            runCatching { entry.listener?.let { entry.player.removeListener(it) } }
            runCatching { entry.player.stop() }
            runCatching { entry.player.clearVideoSurface() }
            runCatching { entry.player.release() }
        }
        entries.clear()
    }

    private fun createLoadControl(): LoadControl {
        val availableMemoryMB = getAvailableMemoryMB()
        val (minBuffer, maxBuffer, playbackBuffer, rebuffer) = if (availableMemoryMB < LOW_MEMORY_THRESHOLD_MB) {
            listOf(500, 2000, 300, 300)
        } else {
            listOf(200, 1000, 150, 150)
        }
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, playbackBuffer, rebuffer)
            .setBackBuffer(500, true)
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .build()
            }
            audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun ensureMainThread(action: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "$action must be called on the main thread"
        }
    }

    private fun urlAt(index: Int): String? =
        if (index in 0 until urlList.size) urlList[index] else null

    private fun nowMs(): Long = SystemClock.elapsedRealtime()
}
