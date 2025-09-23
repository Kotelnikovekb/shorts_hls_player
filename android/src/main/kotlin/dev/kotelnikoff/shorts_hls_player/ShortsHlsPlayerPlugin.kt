package dev.kotelnikoff.shorts_hls_player

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    private var pool: PlayerPool? = null

    private val textureSlots = mutableMapOf<Int, TextureSlot>()

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
    private fun emitProgress(index: Int, url: String, pos: Long, dur: Long, buf: Long) =
        emit(mapOf("type" to "progress", "index" to index, "url" to url, "posMs" to pos, "durMs" to dur, "bufMs" to buf))

    /** ЕДИНСТВЕННЫЙ ensurePool() */
    private fun ensurePool() {
        if (pool != null) return
        pool = PlayerPool(
            context,
            onWatched = { idx, url -> emitWatched(idx, url) },
            onProgress = { idx, url, pos, dur, buf -> emitProgress(idx, url, pos, dur, buf) },
            onReady = { idx -> emitReady(idx) },
            onBuffering = { idx, isBuf -> if (isBuf) emitBufferingStart(idx) else emitBufferingEnd(idx) }
        )
    }

    private fun releaseAll() {
        textureSlots.values.forEach { runCatching { it.release() } }
        textureSlots.clear()
        pool?.release()
        pool = null
    }

    // ---- MethodChannel
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try { handle(call, result) }
        catch (t: Throwable) { result.error("PLUGIN_ERROR", t.message, null) }
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> {
                ensurePool()
                val looping = call.argument<Boolean>("looping") ?: false
                val muted = call.argument<Boolean>("muted") ?: false
                val volume = (call.argument<Number>("volume") ?: 1.0).toFloat()
                pool?.setLooping(looping)
                pool?.setMuted(muted)
                pool?.setVolume(volume)

                call.argument<String>("quality")?.let { applyQualityPreset(it) }

                val progressEnabled = call.argument<Boolean>("progressEnabled") ?: false
                val intervalMs =
                    call.argument<Number>("progressIntervalMs")?.toLong()
                        ?: call.argument<Number>("progressInterval")?.toLong()
                pool?.setProgressTracking(progressEnabled, intervalMs)
                result.success(null)
            }

            "appendUrls" -> {
                ensurePool()
                val urls = call.argument<List<String>>("urls") ?: emptyList()
                urls.forEachIndexed { i, u -> pool?.setUrl(i, u) }
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

            "play" -> { ensurePool(); pool?.play(); result.success(null) }
            "pause" -> { ensurePool(); pool?.pause(); result.success(null) }
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

            "getThumbnail" -> { result.success(null) }

            "disposeIndex" -> {
                val index = (call.argument<Number>("index") ?: -1).toInt()
                if (index < 0) { result.error("INVALID_INDEX", "Index must be >= 0", null); return }
                textureSlots.remove(index)?.release()
                pool?.disposeIndex(index)
                result.success(null)
            }

            "release" -> { releaseAll(); result.success(null) }

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