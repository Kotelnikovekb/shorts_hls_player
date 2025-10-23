package dev.kotelnikoff.shorts_hls_player

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.kotelnikoff.shorts_hls_player.cache.CacheHolder
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry

class ShortsHlsPlayerPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler {

    private lateinit var context: Context
    private lateinit var textures: TextureRegistry
    private lateinit var methods: MethodChannel

    private lateinit var events: EventChannel
    @Volatile private var eventSink: EventChannel.EventSink? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var pool: PlayerPool? = null

    private val textureSlots = java.util.concurrent.ConcurrentHashMap<Int, TextureSlot>()
    private var poolConfig: PlayerPool.Config = PlayerPool.Config()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        textures = binding.textureRegistry

        methods = MethodChannel(binding.binaryMessenger, "shorts_hls_player/methods")
        methods.setMethodCallHandler(this)

        events = EventChannel(binding.binaryMessenger, "shorts_hls_player/events")
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink?) { eventSink = sink }
            override fun onCancel(args: Any?) { eventSink = null }
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods.setMethodCallHandler(null)
        eventSink = null
        releaseAll()
    }

    // ---- helpers
    private fun emit(map: Map<String, Any?>) { eventSink?.success(map) }
    private fun emitReady(index: Int) = emit(mapOf("type" to "ready", "index" to index))
    private fun emitBufferingStart(index: Int) = emit(mapOf("type" to "bufferingStart", "index" to index))
    private fun emitBufferingEnd(index: Int) = emit(mapOf("type" to "bufferingEnd", "index" to index))
    private fun emitWatched(index: Int, url: String) = emit(mapOf("type" to "watched", "index" to index, "url" to url))
    private fun emitFirstFrame(index: Int) = emit(mapOf("type" to "firstFrame", "index" to index))
    private fun emitProgress(index: Int, url: String, pos: Long, dur: Long, buf: Long) =
        emit(mapOf("type" to "progress", "index" to index, "url" to url, "posMs" to pos, "durMs" to dur, "bufMs" to buf))
    private fun emitMetrics(index: Int, metrics: PlayerPool.MetricsSnapshot) = emit(
        mapOf(
            "type" to "metrics",
            "index" to index,
            "startupMs" to metrics.startupMs.coerceToIntOrNull(),
            "firstFrameMs" to metrics.firstFrameMs.coerceToIntOrNull(),
            "rebufferCount" to metrics.rebufferCount,
            "rebufferDurationMs" to metrics.rebufferDurationMs.coerceToInt(),
            "lastRebufferDurationMs" to metrics.lastRebufferDurationMs.coerceToIntOrNull()
        )
    )

    private fun Long.coerceToInt(): Int =
        if (this > Int.MAX_VALUE.toLong()) Int.MAX_VALUE else this.toInt()

    private fun Long?.coerceToIntOrNull(): Int? = this?.coerceToInt()

    /** ЕДИНСТВЕННЫЙ ensurePool() */
    private fun ensurePool() {
        if (pool != null) return
        pool = PlayerPool(
            context,
            poolConfig,
            onWatched = { idx, url -> emitWatched(idx, url) },
            onProgress = { idx, url, pos, dur, buf -> emitProgress(idx, url, pos, dur, buf) },
            onReady = { idx -> emitReady(idx) },
            onBuffering = { idx, isBuf -> if (isBuf) emitBufferingStart(idx) else emitBufferingEnd(idx) },
            onFirstFrame = { idx -> emitFirstFrame(idx) },
            onMetrics = { idx, metrics -> emitMetrics(idx, metrics) },
            onVideoSizeChanged = { idx, w, h ->
                textureSlots[idx]?.let { slot ->
                    runCatching { slot.updateBufferSize(w, h) }
                }
            }
        )
    }

    private fun releaseAll() {
        val slotsToRelease = textureSlots.values.toList()
        textureSlots.clear()
        slotsToRelease.forEach {
            runCatching {
                it.release()
            }
        }

        val currentPool = pool
        pool = null
        currentPool?.release()

        runCatching {
            CacheHolder.release()
        }
    }

    // ---- MethodChannel
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try { handle(call, result) }
        catch (t: Throwable) { result.error("PLUGIN_ERROR", t.message, null) }
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> {
                val looping = call.argument<Boolean>("looping") ?: false
                val muted = call.argument<Boolean>("muted") ?: false
                val volume = (call.argument<Number>("volume") ?: 1.0).toFloat()
                val maxPlayers = call.argument<Number>("maxActivePlayers")?.toInt()?.coerceAtLeast(1)
                val prefetchLimit = call.argument<Number>("prefetchBytesLimit")?.toLong()?.coerceAtLeast(0L)
                val progressDefault = call.argument<Number>("progressIntervalMsDefault")?.toLong()?.coerceAtLeast(50L)
                poolConfig = poolConfig.copy(
                    maxActivePlayers = maxPlayers ?: poolConfig.maxActivePlayers,
                    prefetchBytesLimit = prefetchLimit ?: poolConfig.prefetchBytesLimit,
                    progressIntervalMsDefault = progressDefault ?: poolConfig.progressIntervalMsDefault
                )
                ensurePool()
                pool?.applyConfig(poolConfig)
                pool?.setLooping(looping)
                pool?.setMuted(muted)
                pool?.setVolume(volume)

                (call.argument<String>("quality") ?: call.argument<String>("qualityPreset"))
                    ?.let { applyQualityPreset(it) }

                val progressEnabledFlat = call.argument<Boolean>("progressEnabled")
                val intervalFlat = call.argument<Number>("progressIntervalMs")?.toLong()
                    ?: call.argument<Number>("progressInterval")?.toLong()
                val trackingMap = call.argument<Map<String, Any?>>("progressTracking")
                val progressEnabled = trackingMap?.get("enabled") as? Boolean ?: progressEnabledFlat ?: false
                val intervalMs = (trackingMap?.get("intervalMs") as? Number)?.toLong() ?: intervalFlat
                pool?.setProgressTracking(progressEnabled, intervalMs)
                result.success(null)
            }

            "appendUrls" -> {
                ensurePool()
                val urls = call.argument<List<String>>("urls") ?: emptyList()
                val replace = call.argument<Boolean>("replace") ?: false
                if (replace) {
                    pool?.replaceUrls(urls)
                } else {
                    urls.forEach { u -> pool?.appendUrl(u) }
                }
                result.success(null)
            }

            "createView", "attach" -> {
                ensurePool()
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index < 0) { result.error("INVALID_INDEX", "Index must be >= 0", null); return }
                val slot = textureSlots.getOrPut(index) { TextureSlot(textures) }
                val textureId = slot.create()
                val surface = slot.getSurface()
                if (surface == null) { result.error("NO_SURFACE", "Surface is null for index=$index", null); return }
                pool?.registerSurface(index, surface)
                result.success(textureId)
            }

            "prime" -> {
                ensurePool()
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index < 0) { result.error("INVALID_INDEX", "Index must be >= 0", null); return }
                pool?.prime(index)
                result.success(null)
            }

            "prewarm" -> {
                ensurePool()
                val next = call.argument<Number>("next")?.toInt()
                val prev = call.argument<Number>("prev")?.toInt()
                pool?.prewarm(next, prev)
                result.success(null)
            }

            "setCurrent" -> {
                ensurePool()
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index < 0) { result.error("INVALID_INDEX", "Index must be >= 0", null); return }
                pool?.switchTo(index)
                result.success(null)
            }

            "play" -> {
                ensurePool()
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index >= 0) {
                    pool?.play(index)
                } else {
                    pool?.play()
                }
                result.success(null)
            }
            "pause" -> {
                ensurePool()
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index >= 0) {
                    pool?.pause(index)
                } else {
                    pool?.pause()
                }
                result.success(null)
            }
            "togglePlayPause" -> { ensurePool(); pool?.togglePlayPause(); result.success(null) }
            "isPaused" -> { ensurePool(); result.success(pool?.isPaused() ?: true) }

            "setMuted" -> { ensurePool(); pool?.setMuted(call.argument<Boolean>("muted") == true); result.success(null) }
            "setVolume" -> {
                ensurePool()
                val v = ((call.argument<Number>("volume") ?: 1.0).toFloat()).coerceIn(0f, 1f)
                pool?.setVolume(v); result.success(null)
            }
            "setLooping" -> { ensurePool(); pool?.setLooping(call.argument<Boolean>("looping") == true); result.success(null) }

            "setQualityPreset" -> { ensurePool(); applyQualityPreset(call.argument<String>("preset") ?: "auto"); result.success(null) }
            "setQuality" -> {
                ensurePool()
                val peakBps = call.argument<Number>("peakBps")?.toInt()
                val maxWidth = call.argument<Number>("maxWidth")?.toInt()
                val maxHeight = call.argument<Number>("maxHeight")?.toInt()
                pool?.setQuality(peakBps, maxWidth, maxHeight)
                result.success(null)
            }

            "setProgressTracking" -> {
                ensurePool()
                val enabled = call.argument<Boolean>("enabled") == true
                val intervalMs = call.argument<Number>("intervalMs")?.toLong()
                pool?.setProgressTracking(enabled, intervalMs)
                result.success(null)
            }

            "getThumbnail" -> {
                ensurePool()
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index < 0) {
                    result.error("INVALID_INDEX", "Index must be >= 0", null)
                    return
                }
                val poolRef = pool
                if (poolRef == null) {
                    result.success(null)
                    return
                }
                poolRef.getThumbnail(index) { data -> result.success(data) }
            }

            "getPlaybackInfo" -> {
                ensurePool()
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index < 0) {
                    result.error("INVALID_INDEX", "Index must be >= 0", null)
                    return
                }
                val info = pool?.getPlaybackInfo(index)
                result.success(info)
            }

            "disposeIndex" -> {
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index < 0) { result.error("INVALID_INDEX", "Index must be >= 0", null); return }
                val slot = textureSlots.remove(index)
                pool?.disposeIndex(index)
                slot?.release()
                result.success(null)
            }

            "release" -> { releaseAll(); result.success(null) }

            "configureCacheSize" -> {
                val maxCacheSizeMb = call.argument<Number>("maxCacheSizeMb")?.toInt()
                CacheHolder.configure(maxCacheSizeMb)
                result.success(null)
            }

            "getCacheState" -> {
                result.success(CacheHolder.getState().name)
            }

            "getCacheStats" -> {
                result.success(CacheHolder.getStats())
            }

            "clearCache" -> {
                CacheHolder.clearCache()
                result.success(null)
            }

            "isCached" -> {
                val url = call.argument<String>("url")
                if (url.isNullOrEmpty()) {
                    result.error("INVALID_URL", "URL cannot be null or empty", null)
                    return
                }
                result.success(CacheHolder.isCached(url))
            }

            "getCachedBytes" -> {
                val url = call.argument<String>("url")
                if (url.isNullOrEmpty()) {
                    result.error("INVALID_URL", "URL cannot be null or empty", null)
                    return
                }
                result.success(CacheHolder.getCachedBytes(url))
            }

            "removeFromCache" -> {
                val url = call.argument<String>("url")
                if (url.isNullOrEmpty()) {
                    result.error("INVALID_URL", "URL cannot be null or empty", null)
                    return
                }
                val success = CacheHolder.removeResource(url)
                result.success(success)
            }
            
            "onAppPaused" -> {
                pool?.onAppPaused()
                result.success(null)
            }
            
            "onAppResumed" -> {
                pool?.onAppResumed()
                result.success(null)
            }
            
            "forceSurfaceRefresh" -> {
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index < 0) {
                    result.error("INVALID_INDEX", "Index must be >= 0", null)
                    return
                }
                pool?.forceSurfaceRefresh(index)
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun applyQualityPreset(preset: String) {
        when (preset) {
            "auto"  -> pool?.setQuality(null, null, null)
            "best"  -> pool?.setQuality(null, null, null)
            "p360"  -> pool?.setQuality(null, null, 360)
            "p480"  -> pool?.setQuality(null, null, 480)
            "p720"  -> pool?.setQuality(null, null, 720)
            "p1080" -> pool?.setQuality(null, null, 1080)
            else    -> pool?.setQuality(null, null, null)
        }
    }
}
