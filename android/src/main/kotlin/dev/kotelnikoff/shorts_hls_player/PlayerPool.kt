package dev.kotelnikoff.shorts_hls_player

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
import android.view.Surface
import android.view.PixelCopy
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import java.util.Locale
import java.util.Collections

internal class PlayerPool(
    private val context: android.content.Context,
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
    private val config: Config = initialConfig.copy()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = CacheHolder.obtain(context)
    private val dataSourceFactory = MediaFactories.dataSourceFactory(context, cache)
    private val mediaSourceFactory = MediaFactories.mediaSourceFactory(context, cache)
    private val prefetcher = Prefetcher(cache, dataSourceFactory, config.prefetchBytesLimit)
    private val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val entries: MutableMap<Int, Entry> = Collections.synchronizedMap(LinkedHashMap(16, 0.75f, true))
    private val surfacesByIndex: MutableMap<Int, Surface> = Collections.synchronizedMap(mutableMapOf())
    private val urls: MutableMap<Int, String> = Collections.synchronizedMap(mutableMapOf())
    
    // Синхронизация для предотвращения race conditions при переключении видео
    private val switchLock = Any()

    private val thumbnailCache: MutableMap<Int, ByteArray> = ConcurrentHashMap()
    private val thumbnailExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    
    // Кэш для оптимизации создания плееров
    private val playerCache: MutableMap<String, ExoPlayer> = ConcurrentHashMap()
    private val maxPlayerCacheSize = 3

    @Volatile
    private var activeIndex: Int? = null

    companion object {
        private const val TAG = "ShortsPlayerPool"
        private val PREVIEW_ATTEMPTS_US = longArrayOf(
            0L,
            100_000L,
            300_000L,
            600_000L,
            1_000_000L,
            1_600_000L,
        )
        private const val LUMA_THRESHOLD = 0.035f
        private const val MAX_LUMA_SAMPLES = 400
        private const val LOW_MEMORY_THRESHOLD_MB = 50L

        private fun isEmulator(): Boolean {
            return (Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.startsWith("unknown")
                    || Build.MODEL.contains("google_sdk")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                    || "google_sdk" == Build.PRODUCT
                    || Build.HARDWARE.contains("goldfish")
                    || Build.HARDWARE.contains("ranchu"))
        }
    }

    @Volatile private var looping: Boolean = false
    @Volatile private var muted: Boolean = false
    @Volatile private var volume: Float = 1f
    @Volatile private var progressEnabled: Boolean = false
    @Volatile private var progressIntervalMs: Long = config.progressIntervalMsDefault

    @Volatile private var qualityMaxHeight: Int? = null
    @Volatile private var qualityMaxWidth: Int? = null
    @Volatile private var qualityPeakBps: Int? = null
    
    // Метрики производительности
    private var switchCount = 0
    private var lastSwitchTime = 0L
    private var averageSwitchTime = 0L
    
    // Состояние приложения
    @Volatile private var isAppInForeground = true
    @Volatile private var wasPlayingBeforeBackground = false

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
        val metrics: SessionMetrics = SessionMetrics(),
        var playerListener: Player.Listener? = null  // Храним ссылку для cleanup
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
        
        prewarmFirstElements()
    }

    private fun ensureEntry(index: Int): Entry? {
        val existing = entries[index]
        if (existing != null) {
            Log.d(TAG, "ensureEntry: Using existing entry for index=$index")
            return existing
        }

        val url = urls[index]
        if (url == null) {
            Log.w(TAG, "ensureEntry: No URL for index=$index")
            return null
        }

        Log.d(TAG, "ensureEntry: Creating new player for index=$index, url=$url")

        val player = try {
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
                setEnableDecoderFallback(true)
                setAllowedVideoJoiningTimeMs(1000)
                setEnableAudioFloatOutput(false)

                if (isEmulator()) {
                    Log.w(TAG, "Emulator detected for index=$index, preferring software decoding")
                    setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                } else {
                    setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                }
            }

            ExoPlayer.Builder(context)
                .setLoadControl(createLoadControl())
                .setMediaSourceFactory(mediaSourceFactory)
                .setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setPriorityTaskManager(androidx.media3.common.PriorityTaskManager())
                .setRenderersFactory(renderersFactory)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ExoPlayer for index=$index", e)
            return null
        }

        try {
            player.videoScalingMode = androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            player.playWhenReady = false
            player.videoChangeFrameRateStrategy = androidx.media3.common.C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            player.skipSilenceEnabled = false
            player.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            try {
                player.setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            } catch (e: SecurityException) {
                Log.w(TAG, "WAKE_LOCK permission not granted, wake mode disabled for index=$index")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure ExoPlayer for index=$index", e)
            runCatching { player.release() }
            return null
        }

        val e = Entry(player = player)
        entries[index] = e

        ensureListener(index, e)
        applyVolume(e)
        applyLooping(e)
        applyQualityTo(e)
        e.player.setMediaItem(MediaItem.fromUri(url))
        prefetcher.prefetch(url)
        
        if (index < 3 && !thumbnailCache.containsKey(index)) {
            getThumbnail(index) { _ -> }
        }

        // если Surface уже зарегистрирован — повесим сразу синхронно
        surfacesByIndex[index]?.let { surface ->
            if (surface.isValid) {
                try {
                    e.player.clearVideoSurface()
                    e.player.setVideoSurface(surface)
                    Log.d(TAG, "ensureEntry: Surface attached for index=$index")
                } catch (ex: Exception) {
                    Log.w(TAG, "Failed to set surface for index=$index", ex)
                    surfacesByIndex.remove(index)
                }
            } else {
                Log.w(TAG, "ensureEntry: Surface invalid for index=$index, removing")
                surfacesByIndex.remove(index)
            }
        }

        Log.d(TAG, "ensureEntry: Player created successfully for index=$index")
        return e
    }

    fun registerSurface(index: Int, surface: Surface) {
        synchronized(surfacesByIndex) {
            if (!surface.isValid) {
                Log.w(TAG, "registerSurface: Invalid surface for index=$index")
                return
            }

            Log.d(TAG, "registerSurface: Registering surface for index=$index")

            val old = surfacesByIndex[index]
            if (old != null && old !== surface) {
                Log.d(TAG, "registerSurface: Clearing old surface for index=$index")
                runCatching {
                    entries[index]?.player?.clearVideoSurface()
                }
            }
            surfacesByIndex[index] = surface

            // Предварительная проверка Surface перед использованием
            if (!isSurfaceUsable(surface)) {
                Log.w(TAG, "registerSurface: Surface not usable for index=$index")
                surfacesByIndex.remove(index)
                return
            }

            drawThumbnailIntoSurface(index, surface)

            entries[index]?.let { entry ->
                if (!surface.isValid) {
                    Log.w(TAG, "registerSurface: Surface became invalid for index=$index")
                    surfacesByIndex.remove(index)
                    return@synchronized
                }

                // Синхронно устанавливаем Surface
                runCatching {
                    entry.player.clearVideoSurface()
                    entry.player.setVideoSurface(surface)
                    Log.d(TAG, "registerSurface: Surface set for index=$index")
                    if (entry.prepared && entry.player.playbackState != Player.STATE_IDLE) {
                        Log.d(TAG, "registerSurface: Player already prepared for index=$index, state=${entry.player.playbackState}")
                    } else if (!entry.prepared) {
                        Log.d(TAG, "registerSurface: Preparing player for index=$index")
                        entry.player.prepare()
                        entry.prepared = true
                    }
                }.onFailure { ex ->
                    Log.e(TAG, "registerSurface: Failed to set surface for index=$index", ex)
                    synchronized(surfacesByIndex) {
                        surfacesByIndex.remove(index)
                    }
                }
            } ?: run {
                Log.d(TAG, "registerSurface: No player entry yet for index=$index, surface registered for later use")
            }
        }
    }
    
    private fun isSurfaceUsable(surface: Surface): Boolean {
        return try {
            surface.isValid
        } catch (e: Exception) {
            Log.w(TAG, "Surface validation failed", e)
            false
        }
    }
    
    private fun ensureSurfaceAttached(index: Int, entry: Entry) {
        val surface = synchronized(surfacesByIndex) {
            surfacesByIndex[index]?.takeIf { it.isValid }
        }
        
        if (surface != null) {
            try {
                Log.d(TAG, "ensureSurfaceAttached: Reattaching surface for index=$index")
                entry.player.clearVideoSurface()
                entry.player.setVideoSurface(surface)
            } catch (e: Exception) {
                Log.e(TAG, "ensureSurfaceAttached: Failed to attach surface for index=$index", e)
                synchronized(surfacesByIndex) {
                    surfacesByIndex.remove(index)
                }
            }
        } else {
            Log.w(TAG, "ensureSurfaceAttached: No valid surface for index=$index")
        }
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
        
        val current = activeIndex
        if (current != null) {
            val nextNext = current + 1
            val prevPrev = current - 1
            if (urls.containsKey(nextNext)) prime(nextNext)
            if (urls.containsKey(prevPrev)) prime(prevPrev)
        }
        
        activeIndex?.let { enforcePoolBudget(it) }
    }
    
    fun prewarmFirstElements() {
        val prefetchCount = minOf(3, urls.size)
        for (i in 0 until prefetchCount) {
            if (!entries.containsKey(i)) {
                prime(i)
            }
        }
    }

    fun switchTo(index: Int) {
        val startTime = nowMs()
        synchronized(switchLock) {
            val e = ensureEntry(index)
        if (e == null) {
            Log.e(TAG, "switchTo: Failed to ensure entry for index=$index")
            return
        }

        val url = urls[index]
        if (url.isNullOrEmpty()) {
            Log.e(TAG, "switchTo: No URL for index=$index")
            return
        }

        val prevIdx = activeIndex
        val prev = prevIdx?.let { entries[it] }
        
        if (prev != null && prev !== e) {
            runCatching {
                Log.d(TAG, "switchTo: Stopping previous player at index=$prevIdx")
                cancelProgressWatcher(prev)
                prev?.player?.pause()
                prev?.player?.playWhenReady = false
                prev?.player?.clearVideoSurface()
            }.onFailure { ex ->
                Log.e(TAG, "switchTo: Error stopping previous player at index=$prevIdx", ex)
            }
        }

        if (!e.prepared) {
            try {
                e.player.prepare()
                e.prepared = true
            } catch (ex: Exception) {
                Log.e(TAG, "switchTo: Failed to prepare player for index=$index", ex)
                return
            }
        } else {
            e.player.seekTo(0)
        }

        // Получаем Surface синхронно
        val surface = synchronized(surfacesByIndex) {
            surfacesByIndex[index]?.takeIf { it.isValid }
        }

        if (surface != null) {
            // Синхронно устанавливаем Surface
            runCatching {
                e.player.clearVideoSurface()
                e.player.setVideoSurface(surface)
                Log.d(TAG, "switchTo: Surface attached for index=$index")
            }.onFailure { ex ->
                Log.e(TAG, "switchTo: Failed to set surface for index=$index", ex)
                synchronized(surfacesByIndex) {
                    surfacesByIndex.remove(index)
                }
            }
        } else {
            synchronized(surfacesByIndex) {
                if (surfacesByIndex[index] != null) {
                    Log.w(TAG, "switchTo: Surface invalid for index=$index, removing")
                    surfacesByIndex.remove(index)
                } else {
                    Log.w(TAG, "switchTo: No surface for index=$index - will wait for registerSurface")
                }
            }
        }

        e.metrics.start(nowMs())
        e.isBuffering = false
        e.firstFrameReported = false
        reportMetrics(index, e.metrics)

        applyVolume(e)
        applyLooping(e)
        prefetcher.prefetch(url)

        e.player.playWhenReady = true
        cancelProgressWatcher(e)
        activeIndex = index
        
        // Убеждаемся что Surface привязан
        ensureSurfaceAttached(index, e)

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
        enforceWindowBudget(index)
        
        prewarm(index + 1, index - 1)
        
        // Обновляем метрики производительности
        val switchTime = nowMs() - startTime
        switchCount++
        averageSwitchTime = (averageSwitchTime * (switchCount - 1) + switchTime) / switchCount
        lastSwitchTime = switchTime
        
        if (switchTime > 100) {
            Log.w(TAG, "Slow switch detected: ${switchTime}ms for index=$index")
        }
        }
    }

    // ---------------- PLAYBACK ----------------

    fun play() {
        requestAudioFocus()
        val idx = activeIndex
        if (idx == null) {
            Log.w(TAG, "play: No active index set")
            return
        }
        val e = entries[idx]
        if (e == null) {
            Log.w(TAG, "play: No entry for activeIndex=$idx")
            return
        }

        runCatching {
            e.player.playWhenReady = true
            e.player.play()
            scheduleProgressWatcher(idx, e)
            Log.d(TAG, "play: Started playback for activeIndex=$idx, state=${e.player.playbackState}")
        }.onFailure { ex ->
            Log.e(TAG, "play: Failed to start playback for activeIndex=$idx", ex)
        }
    }
    fun play(index: Int) {
        synchronized(switchLock) {
            requestAudioFocus()
            val e = ensureEntry(index)
        if (e == null) {
            Log.e(TAG, "play: Failed to ensure entry for index=$index")
            return
        }

        // Останавливаем предыдущий активный плеер если он отличается
        val prevIdx = activeIndex
        val prev = prevIdx?.let { entries[it] }
        if (prev != null && prev !== e) {
            runCatching {
                Log.d(TAG, "play: Stopping previous player at index=$prevIdx")
                cancelProgressWatcher(prev)
                prev?.player?.pause()
                prev?.player?.playWhenReady = false
                prev?.player?.clearVideoSurface()
            }.onFailure { ex ->
                Log.e(TAG, "play: Error stopping previous player at index=$prevIdx", ex)
            }
        }

        val surface = synchronized(surfacesByIndex) {
            surfacesByIndex[index]?.takeIf { it.isValid }
        }

        if (surface != null) {
            // Синхронно устанавливаем Surface
            runCatching {
                e.player.clearVideoSurface()
                e.player.setVideoSurface(surface)
                Log.d(TAG, "play: Surface attached for index=$index")
            }.onFailure { ex ->
                Log.e(TAG, "play: Failed to attach surface for index=$index", ex)
                synchronized(surfacesByIndex) {
                    surfacesByIndex.remove(index)
                }
            }
        } else {
            synchronized(surfacesByIndex) {
                if (surfacesByIndex[index] != null) {
                    Log.w(TAG, "play: Surface invalid for index=$index, removing")
                    surfacesByIndex.remove(index)
                } else {
                    Log.w(TAG, "play: No surface for index=$index - video may not render!")
                }
            }
        }

        if (!e.prepared) {
            try {
                e.player.prepare()
                e.prepared = true
            } catch (ex: Exception) {
                Log.e(TAG, "play: Failed to prepare player for index=$index", ex)
                return
            }
        }

        activeIndex = index
        ensureSurfaceAttached(index, e)
        runCatching {
            e.player.playWhenReady = true
            e.player.play()
            scheduleProgressWatcher(index, e)
        }.onFailure { ex ->
            Log.e(TAG, "switchTo: Failed to start playback for index=$index", ex)
        }
        prewarm(index + 1, index - 1)
        }
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
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
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

    fun pause() {
        abandonAudioFocus()
        val idx = activeIndex ?: return
        val e = entries[idx] ?: return
        e.player.pause()
        e.player.playWhenReady = false
        cancelProgressWatcher(e)
        Log.d(TAG, "pause: Paused active player at index=$idx")
    }

    fun pause(index: Int) {
        abandonAudioFocus()
        val e = entries[index] ?: return
        e.player.pause()
        e.player.playWhenReady = false
        cancelProgressWatcher(e)
        Log.d(TAG, "pause: Paused player at index=$index")
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
    
    fun getPerformanceMetrics(): Map<String, Any> {
        val availableMemoryMB = getAvailableMemoryMB()
        return mapOf(
            "switchCount" to switchCount,
            "lastSwitchTime" to lastSwitchTime,
            "averageSwitchTime" to averageSwitchTime,
            "activePlayers" to entries.size,
            "thumbnailCacheSize" to thumbnailCache.size,
            "availableMemoryMB" to availableMemoryMB,
            "isLowMemory" to (availableMemoryMB < LOW_MEMORY_THRESHOLD_MB)
        )
    }
    
    fun forceSurfaceRefresh(index: Int) {
        val entry = entries[index] ?: return
        Log.d(TAG, "forceSurfaceRefresh: Refreshing surface for index=$index")
        ensureSurfaceAttached(index, entry)
    }
    
    fun onAppPaused() {
        Log.d(TAG, "onAppPaused: App went to background")
        isAppInForeground = false
        wasPlayingBeforeBackground = activeIndex?.let { entries[it]?.player?.isPlaying } ?: false
        
        if (wasPlayingBeforeBackground) {
            Log.d(TAG, "onAppPaused: Pausing playback due to background")
            pause()
        }
    }
    
    fun onAppResumed() {
        Log.d(TAG, "onAppResumed: App came to foreground")
        isAppInForeground = true
        
        if (wasPlayingBeforeBackground) {
            Log.d(TAG, "onAppResumed: Resuming playback")
            play()
        }
    }

    // ---------------- DISPOSE / RELEASE ----------------

    fun disposeIndex(index: Int) {
        val e = entries.remove(index) ?: return
        cancelProgressWatcher(e)
        runCatching {
            e.playerListener?.let { e.player.removeListener(it) }
            e.player.stop()
            e.player.clearVideoSurface()
            e.player.clearMediaItems()
            e.player.release()
        }.onFailure { ex ->
            Log.e(TAG, "disposeIndex: Error disposing player at index=$index", ex)
        }
        surfacesByIndex.remove(index)
        if (activeIndex == index) activeIndex = null
        thumbnailCache.remove(index)
        Log.d(TAG, "disposeIndex: Disposed player at index=$index")
    }

    fun release() {
        abandonAudioFocus()

        synchronized(entries) {
            entries.values.forEach { e ->
                cancelProgressWatcher(e)
                runCatching {
                    e.playerListener?.let { e.player.removeListener(it) }
                    e.player.stop()
                    e.player.clearVideoSurface()
                    e.player.clearMediaItems()
                    e.player.release()
                }.onFailure { ex ->
                    Log.e(TAG, "release: Error releasing player", ex)
                }
            }
            entries.clear()
        }

        synchronized(surfacesByIndex) {
            surfacesByIndex.clear()
        }
        activeIndex = null
        thumbnailCache.clear()

        runCatching {
            prefetcher.shutdown()
        }.onFailure { ex ->
            Log.e(TAG, "release: Error shutting down prefetcher", ex)
        }

        runCatching {
            thumbnailExecutor.shutdownNow()
        }.onFailure { ex ->
            Log.e(TAG, "release: Error shutting down thumbnail executor", ex)
        }

        Log.d(TAG, "PlayerPool released")
    }

    // ---------------- INTERNAL ----------------

    private fun ensureListener(index: Int, e: Entry) {
        if (e.listenerAdded) return

        val listener = object : Player.Listener {
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
                    if (isPlaying) {
                        scheduleProgressWatcher(index, e)
                        // Проверяем Surface при начале воспроизведения
                        ensureSurfaceAttached(index, e)
                    } else {
                        cancelProgressWatcher(e)
                    }
                }
            }
            override fun onRenderedFirstFrame() {
                val now = nowMs()
                e.metrics.markBufferEnd(now)
                e.metrics.markFirstFrame(now)
                if (!e.firstFrameReported) {
                    e.firstFrameReported = true
                    onFirstFrame?.invoke(index)
                    Log.d(TAG, "First frame rendered for index=$index")
                }
                reportMetrics(index, e.metrics)
                captureSnapshotForCache(index, e)
            }
            
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                Log.d(TAG, "Video size changed for index=$index: ${videoSize.width}x${videoSize.height}")
                try {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        onVideoSizeChanged?.invoke(index, videoSize.width, videoSize.height)
                    }
                } catch (_: Throwable) {}
                if (activeIndex == index) {
                    ensureSurfaceAttached(index, e)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Player error at index=$index: ${error.message}", error)
                e.metrics.markBufferEnd(nowMs())
                if (e.isBuffering) {
                    e.isBuffering = false
                    onBuffering?.invoke(index, false)
                }

                val isDecoderError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                     error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
                                     error is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException

                when {
                    isDecoderError -> {
                        Log.w(TAG, "Decoder error at index=$index, attempting recovery...")
                        mainHandler.postDelayed({
                            runCatching {
                                val currentPos = e.player.currentPosition
                                e.player.stop()
                                e.player.prepare()
                                e.player.seekTo(currentPos)
                                if (activeIndex == index) {
                                    e.player.playWhenReady = true
                                    e.player.play()
                                }
                                Log.d(TAG, "Decoder recovery attempted for index=$index")
                            }.onFailure { ex ->
                                Log.e(TAG, "Decoder recovery failed for index=$index", ex)
                                disposeIndex(index)
                            }
                        }, 500)
                    }
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                        Log.w(TAG, "Network error at index=$index, will retry")
                    }
                    else -> {
                        Log.e(TAG, "Fatal error at index=$index, disposing player")
                        mainHandler.post { disposeIndex(index) }
                    }
                }
            }
        }

        e.player.addListener(listener)
        e.playerListener = listener
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

    private fun captureSnapshotForCache(index: Int, entry: Entry) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (thumbnailCache.containsKey(index)) return
        val surface = synchronized(surfacesByIndex) {
            surfacesByIndex[index]?.takeIf { it.isValid }
        } ?: return
        val vs = entry.player.videoSize
        val width = if (vs.width > 0) vs.width else 360
        val height = if (vs.height > 0) vs.height else 640
        if (width <= 0 || height <= 0) return
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    thumbnailExecutor.execute {
                        val bytes = compressBitmap(bitmap)
                        if (bytes != null) {
                            thumbnailCache[index] = bytes
                            Log.d(TAG, "Cached snapshot from playback index=$index")
                        }
                        bitmap.recycle()
                    }
                } else {
                    bitmap.recycle()
                }
            }, mainHandler)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to capture snapshot for index=$index", t)
            bitmap.recycle()
        }
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
        val availableMemoryMB = getAvailableMemoryMB()
        val effectiveLimit = if (availableMemoryMB < LOW_MEMORY_THRESHOLD_MB) {
            Log.w(TAG, "Low memory: ${availableMemoryMB}MB, reducing pool size")
            thumbnailCache.clear()
            System.gc()
            1
        } else {
            config.maxActivePlayers.coerceAtLeast(1)
        }

        if (entries.size <= effectiveLimit) return

        val candidates: MutableList<Int> = synchronized(entries) {
            entries.keys.filter { it != anchorIndex }.toMutableList()
        }

        candidates.sortByDescending { distance(anchorIndex, it) }
        while (entries.size > effectiveLimit && candidates.isNotEmpty()) {
            val victim = candidates.removeAt(0)
            disposeIndex(victim)
        }
    }
    
    /** Ограничиваем окно плееров как в iOS - только активный + соседи в радиусе 2 */
    private fun enforceWindowBudget(anchorIndex: Int) {
        val toDispose = entries.keys.filter { abs(it - anchorIndex) > 2 }
        toDispose.forEach { disposeIndex(it) }
    }

    private fun getAvailableMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val availableMemory = maxMemory - (totalMemory - freeMemory)
        val availableMB = availableMemory / (1024 * 1024)
        
        // Автоматическая очистка кэша при критической нехватке памяти
        if (availableMB < LOW_MEMORY_THRESHOLD_MB / 2) {
            Log.w(TAG, "Critical memory: ${availableMB}MB, clearing caches")
            clearCaches()
        }
        
        return availableMB
    }
    
    private fun clearCaches() {
        thumbnailCache.clear()
        playerCache.clear()
        System.gc()
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

        if (index < 3) {
            generateThumbnailSync(index, callback)
        } else {
            thumbnailExecutor.execute {
                val data = runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, Uri.parse(url))
                        extractMeaningfulThumbnail(index, retriever)
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()

                if (data != null) {
                    thumbnailCache[index] = data
                    synchronized(surfacesByIndex) {
                        surfacesByIndex[index]?.takeIf { it.isValid }?.let { s ->
                            drawThumbnailIntoSurface(index, s)
                        }
                    }
                } else {
                    Log.w(TAG, "Thumbnail extraction failed for index=$index url=$url")
                }
                mainHandler.post { callback(data) }
            }
        }
    }
    
    private fun generateThumbnailSync(index: Int, callback: (ByteArray?) -> Unit) {
        val url = urls[index] ?: return
        thumbnailExecutor.execute {
            val data = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(url))
                    extractMeaningfulThumbnail(index, retriever)
                } finally {
                    retriever.release()
                }
            }.getOrNull()

            if (data != null) {
                thumbnailCache[index] = data
                synchronized(surfacesByIndex) {
                    surfacesByIndex[index]?.takeIf { it.isValid }?.let { s ->
                        drawThumbnailIntoSurface(index, s)
                    }
                }
            }
            mainHandler.post { callback(data) }
        }
    }

    private fun extractMeaningfulThumbnail(index: Int, retriever: MediaMetadataRetriever): ByteArray? {
        for (timeUs in PREVIEW_ATTEMPTS_US) {
            val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: continue
            val luminance = averageLuminance(bmp)
            val lumaLabel = String.format(Locale.US, "%.3f", luminance)
            val meaningful = luminance >= LUMA_THRESHOLD
            if (meaningful) {
                val bytes = compressBitmap(bmp)
                bmp.recycle()
                if (bytes != null) {
                    Log.d(
                        TAG,
                        "Thumbnail captured index=$index timeUs=$timeUs luma=$lumaLabel"
                    )
                    return bytes
                }
            } else {
                Log.d(
                    TAG,
                    "Discarded dark frame index=$index timeUs=$timeUs luma=$lumaLabel"
                )
                bmp.recycle()
            }
        }

        val fallback = retriever.frameAtTime
        if (fallback != null) {
            val luminance = averageLuminance(fallback)
            val lumaLabel = String.format(Locale.US, "%.3f", luminance)
            if (luminance >= LUMA_THRESHOLD) {
                val bytes = compressBitmap(fallback)
                fallback.recycle()
                if (bytes != null) {
                    Log.d(
                        TAG,
                        "Thumbnail fallback frameAtTime used index=$index luma=$lumaLabel"
                    )
                    return bytes
                }
            } else {
                Log.d(
                    TAG,
                    "Fallback frame still dark index=$index luma=$lumaLabel"
                )
                fallback.recycle()
            }
        }
        return null
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
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return 0f
        val stepX = maxOf(1, w / 24)
        val stepY = maxOf(1, h / 24)
        var samples = 0
        var total = 0f
        val maxSamples = MAX_LUMA_SAMPLES
        var y = 0
        while (y < h && samples < maxSamples) {
            var x = 0
            while (x < w && samples < maxSamples) {
                val color = bmp.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                total += luma
                samples++
                x += stepX
            }
            y += stepY
        }
        if (samples == 0) return 0f
        return total / (samples * 255f)
    }

    /**
     * Отрисовать превью-кадр в Surface как плейсхолдер до первого кадра видео.
     * Масштабирование по принципу centerCrop (аналог BoxFit.cover / resizeAspectFill).
     */
    private fun drawThumbnailIntoSurface(index: Int, surface: Surface) {
        if (!surface.isValid) return
        val bytes = thumbnailCache[index] ?: return
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        runCatching {
            if (!surface.isValid) {
                bmp.recycle()
                return
            }
            val canvas: Canvas = surface.lockCanvas(null)
            try {
                canvas.drawColor(Color.BLACK)

                val dst = RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
                val src = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())

                val m = Matrix()
                val scale = maxOf(dst.width() / src.width(), dst.height() / src.height())
                val scaledW = src.width() * scale
                val scaledH = src.height() * scale
                val dx = (dst.width() - scaledW) / 2f
                val dy = (dst.height() - scaledH) / 2f
                m.postScale(scale, scale)
                m.postTranslate(dx, dy)

                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(bmp, m, p)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            bmp.recycle()
        }.onFailure { ex ->
            Log.w(TAG, "Failed to draw thumbnail for index=$index", ex)
            bmp.recycle()
        }
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
}
