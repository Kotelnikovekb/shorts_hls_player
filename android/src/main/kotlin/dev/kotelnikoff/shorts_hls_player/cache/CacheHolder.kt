package dev.kotelnikoff.shorts_hls_player.cache

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal object CacheHolder {
    private const val TAG = "CacheHolder"
    private const val CACHE_DIR = "shorts_media_cache"
    private const val DEFAULT_CACHE_LIMIT_MB = 512
    private const val MAX_CACHE_RETRY_ATTEMPTS = 3
    private const val MIN_FREE_SPACE_MB = 100L
    private const val PREFS_NAME = "shorts_cache_prefs"
    private const val KEY_CACHE_HITS = "cache_hits"
    private const val KEY_CACHE_MISSES = "cache_misses"
    private const val KEY_ACCESS_PATTERNS = "access_patterns"
    private const val DEFAULT_TTL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    private const val ANALYTICS_WINDOW_MS = 7 * 24 * 60 * 60 * 1000L

    @Volatile
    private var cache: SimpleCache? = null
    @Volatile
    private var databaseProvider: DatabaseProvider? = null
    @Volatile
    private var configuredLimitMb: Int = DEFAULT_CACHE_LIMIT_MB
    @Volatile
    private var initializationFailed: Boolean = false
    @Volatile
    private var cacheTtlMs: Long = DEFAULT_TTL_MS
    @Volatile
    private var wifiOnlyMode: Boolean = false

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<CacheListener>()
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val pinned = ConcurrentHashMap<String, Long>()
    private val cacheTimestamps = ConcurrentHashMap<String, Long>()
    private val accessPatterns = ConcurrentHashMap<String, AccessPattern>()
    private val corruptedFiles = ConcurrentHashMap.newKeySet<String>()
    private val urlPriority = ConcurrentHashMap<String, UrlPriority>()
    private val prefetchScores = ConcurrentHashMap<String, Double>()
    private val requestTraces = ConcurrentHashMap<String, MutableList<RequestTrace>>()

    private var prefs: SharedPreferences? = null
    @Volatile
    private var enableIntegrityCheck = false
    @Volatile
    private var enablePrefetchPrediction = false
    @Volatile
    private var enableRequestTracing = false

    enum class UrlPriority(val weight: Double) {
        CRITICAL(1.0),      // Must keep (user's content, favorites)
        HIGH(0.8),          // Important (trending, popular)
        NORMAL(0.5),        // Regular content
        LOW(0.3),           // Old or rarely accessed
        EXPENDABLE(0.1)     // Can delete anytime
    }

    data class AccessPattern(
        var accessCount: Int = 0,
        var lastAccess: Long = 0,
        var firstAccess: Long = 0,
        var avgAccessInterval: Long = 0
    )

    data class RequestTrace(
        val timestamp: Long,
        val url: String,
        val source: String,
        val hit: Boolean,
        val durationMs: Long
    )

    data class PrefetchPrediction(
        val url: String,
        val score: Double,
        val reason: String,
        val confidence: Double
    )

    data class CacheAnalytics(
        val totalRequests: Long,
        val hitRate: Double,
        val avgResponseTime: Long,
        val topUrls: List<Pair<String, Int>>,
        val timeDistribution: Map<String, Int>,
        val storageEfficiency: Double,
        val recommendations: List<String>
    )

    data class IntegrityReport(
        val totalFiles: Int,
        val validFiles: Int,
        val corruptedFiles: Int,
        val missingFiles: Int,
        val totalSize: Long,
        val corruptedUrls: List<String>
    )

    interface CacheListener {
        fun onCacheCleared(itemsRemoved: Int)
        fun onResourceRemoved(url: String)
        fun onLowDiskSpace(availableMb: Long)
        fun onCacheLimitReached(utilizationPercent: Int)
        fun onExpiredItemsRemoved(count: Int)
    }

    data class CacheConfig(
        val maxSizeMb: Int = DEFAULT_CACHE_LIMIT_MB,
        val ttlMs: Long = DEFAULT_TTL_MS,
        val wifiOnlyMode: Boolean = false,
        val autoCleanupExpired: Boolean = true
    )

    enum class CacheState {
        UNINITIALIZED,
        INITIALIZING,
        READY,
        FAILED,
        RELEASED
    }

    @Volatile
    private var state: CacheState = CacheState.UNINITIALIZED

    fun addListener(listener: CacheListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CacheListener) {
        listeners.remove(listener)
    }

    fun configure(maxCacheSizeMb: Int?, hotReload: Boolean = false) {
        synchronized(this) {
            val newLimit = (maxCacheSizeMb ?: DEFAULT_CACHE_LIMIT_MB).coerceAtLeast(0)
            if (newLimit == configuredLimitMb) return

            val oldLimit = configuredLimitMb
            configuredLimitMb = newLimit

            if (state == CacheState.READY) {
                if (hotReload) {
                    Log.i(TAG, "Hot-reloading cache: $oldLimit MB -> $newLimit MB")
                    backgroundExecutor.execute {
                        synchronized(this) {
                            try {
                                val context = cache?.let {
                                    (it as? SimpleCache)?.let { _ ->
                                        null
                                    }
                                }
                                release()
                                context?.let { obtain(it) }
                                Log.i(TAG, "Cache hot-reloaded successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to hot-reload cache", e)
                                configuredLimitMb = oldLimit
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Cache already initialized with ${oldLimit}MB. New limit: ${newLimit}MB will apply after release()")
                }
            }
        }
    }

    fun isInitialized(): Boolean = state == CacheState.READY

    fun getState(): CacheState = state

    fun obtain(context: Context): SimpleCache {
        cache?.let {
            if (state == CacheState.READY) return it
        }

        synchronized(this) {
            cache?.let {
                if (state == CacheState.READY) return it
            }

            if (state == CacheState.INITIALIZING) {
                throw IllegalStateException("Cache is already being initialized")
            }

            if (initializationFailed) {
                Log.w(TAG, "Previous initialization failed, attempting recovery...")
                cleanupResources()
                initializationFailed = false
            }

            state = CacheState.INITIALIZING

            return try {
                prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                loadPersistedMetrics()

                val dir = getCacheDirectory(context)
                val provider = getOrCreateDatabaseProvider(context)
                val evictor = createEvictor(configuredLimitMb)

                Log.d(TAG, "Initializing cache: dir=${dir.absolutePath}, limit=${configuredLimitMb}MB, ttl=${cacheTtlMs}ms")

                val newCache = tryCreateCache(dir, evictor, provider)

                cache = newCache
                state = CacheState.READY
                Log.d(TAG, "Cache initialized successfully")

                scheduleExpiredCleanup()

                newCache
            } catch (e: Exception) {
                handleInitializationFailure(e)
                throw e
            }
        }
    }

    private fun loadPersistedMetrics() {
        prefs?.let {
            cacheHits.set(it.getLong(KEY_CACHE_HITS, 0))
            cacheMisses.set(it.getLong(KEY_CACHE_MISSES, 0))
            Log.d(TAG, "Loaded metrics: ${cacheHits.get()} hits, ${cacheMisses.get()} misses")
        }
    }

    private fun persistMetrics() {
        prefs?.edit()?.apply {
            putLong(KEY_CACHE_HITS, cacheHits.get())
            putLong(KEY_CACHE_MISSES, cacheMisses.get())
            apply()
        }
    }

    private fun getCacheDirectory(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR)

        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw IOException("Failed to create cache directory: ${dir.absolutePath}")
            }
        }

        if (!dir.canWrite()) {
            throw IOException("Cache directory is not writable: ${dir.absolutePath}")
        }

        checkDiskSpace(dir)

        return dir
    }

    private fun checkDiskSpace(dir: File) {
        try {
            val stat = StatFs(dir.absolutePath)
            val availableBytes = stat.availableBytes
            val availableMb = availableBytes / (1024 * 1024)

            if (availableMb < MIN_FREE_SPACE_MB) {
                Log.w(TAG, "Low disk space: ${availableMb}MB available (minimum: ${MIN_FREE_SPACE_MB}MB)")
                listeners.forEach { it.onLowDiskSpace(availableMb) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check disk space", e)
        }
    }

    fun getAvailableDiskSpaceMb(): Long {
        return try {
            val dir = File(android.os.Environment.getDataDirectory().absolutePath)
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get available disk space", e)
            0L
        }
    }

    private fun getOrCreateDatabaseProvider(context: Context): DatabaseProvider {
        return databaseProvider ?: try {
            StandaloneDatabaseProvider(context.applicationContext).also {
                databaseProvider = it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create database provider", e)
            throw e
        }
    }

    private fun tryCreateCache(
        dir: File,
        evictor: CacheEvictor,
        provider: DatabaseProvider
    ): SimpleCache {
        var lastException: Exception? = null

        repeat(MAX_CACHE_RETRY_ATTEMPTS) { attempt ->
            try {
                return SimpleCache(dir, evictor, provider)
            } catch (e: androidx.media3.database.DatabaseIOException) {
                Log.w(TAG, "Cache database corrupted (attempt ${attempt + 1}/$MAX_CACHE_RETRY_ATTEMPTS)", e)
                lastException = e

                if (attempt < MAX_CACHE_RETRY_ATTEMPTS - 1) {
                    deleteCacheFiles(dir)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create cache (attempt ${attempt + 1}/$MAX_CACHE_RETRY_ATTEMPTS)", e)
                lastException = e
                throw e
            }
        }

        throw lastException ?: IOException("Failed to create cache after $MAX_CACHE_RETRY_ATTEMPTS attempts")
    }

    private fun deleteCacheFiles(dir: File) {
        try {
            Log.w(TAG, "Deleting corrupted cache files...")
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete cache files", e)
        }
    }

    private fun handleInitializationFailure(e: Exception) {
        Log.e(TAG, "Cache initialization failed", e)
        initializationFailed = true
        state = CacheState.FAILED
        cleanupResources()
    }

    private fun createEvictor(limitMb: Int): CacheEvictor {
        return if (limitMb <= 0) {
            Log.d(TAG, "Unlimited cache mode")
            NoOpCacheEvictor()
        } else {
            val bytes = limitMb.toLong() * 1024 * 1024
            LeastRecentlyUsedCacheEvictor(bytes)
        }
    }

    fun getStats(): Map<String, Any>? {
        val c = cache ?: return null

        return synchronized(this) {
            try {
                val keys = c.keys.toSet()
                val totalSize = keys.sumOf { key ->
                    try {
                        c.getCachedSpans(key)?.sumOf { it.length } ?: 0L
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get cached spans for key: $key", e)
                        0L
                    }
                }

                val utilizationPercent = if (configuredLimitMb > 0) {
                    (totalSize * 100 / (configuredLimitMb.toLong() * 1024 * 1024)).coerceAtMost(100).toInt()
                } else 0

                if (utilizationPercent > 90) {
                    listeners.forEach { it.onCacheLimitReached(utilizationPercent) }
                }

                val hits = cacheHits.get()
                val misses = cacheMisses.get()
                val hitRate = if (hits + misses > 0) {
                    (hits * 100 / (hits + misses)).toInt()
                } else 0

                mapOf(
                    "state" to state.name,
                    "cacheSpace" to c.cacheSpace,
                    "totalBytes" to totalSize,
                    "keys" to keys.size,
                    "configuredLimitMb" to configuredLimitMb,
                    "configuredLimitBytes" to (configuredLimitMb.toLong() * 1024 * 1024),
                    "utilizationPercent" to utilizationPercent,
                    "cacheHits" to hits,
                    "cacheMisses" to misses,
                    "cacheHitRate" to hitRate,
                    "availableDiskSpaceMb" to getAvailableDiskSpaceMb()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect cache stats", e)
                mapOf(
                    "state" to state.name,
                    "error" to (e.message ?: "Unknown error")
                )
            }
        }
    }

    fun getStatsAsync(callback: (Map<String, Any>?) -> Unit) {
        backgroundExecutor.execute {
            val stats = getStats()
            callback(stats)
        }
    }

    fun clearCache() {
        synchronized(this) {
            val c = cache ?: return
            try {
                val keys = c.keys.toList()
                var successCount = 0
                var failCount = 0

                keys.forEach { key ->
                    try {
                        c.removeResource(key)
                        successCount++
                    } catch (e: Exception) {
                        failCount++
                        Log.w(TAG, "Failed to remove key: $key", e)
                    }
                }

                Log.d(TAG, "Cache cleared: $successCount succeeded, $failCount failed (total: ${keys.size})")
                listeners.forEach { it.onCacheCleared(successCount) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear cache", e)
            }
        }
    }

    fun clearCacheAsync(callback: ((Int) -> Unit)? = null) {
        backgroundExecutor.execute {
            synchronized(this) {
                val c = cache ?: run {
                    callback?.invoke(0)
                    return@execute
                }
                try {
                    val keys = c.keys.toList()
                    var successCount = 0

                    keys.forEach { key ->
                        try {
                            c.removeResource(key)
                            successCount++
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to remove key: $key", e)
                        }
                    }

                    Log.d(TAG, "Cache cleared async: $successCount items")
                    listeners.forEach { it.onCacheCleared(successCount) }
                    callback?.invoke(successCount)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear cache async", e)
                    callback?.invoke(0)
                }
            }
        }
    }

    fun removeResource(url: String): Boolean {
        return try {
            cache?.removeResource(url)
            Log.d(TAG, "Removed resource: $url")
            listeners.forEach { it.onResourceRemoved(url) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove resource: $url", e)
            false
        }
    }

    fun isCached(url: String): Boolean {
        val c = cache ?: run {
            cacheMisses.incrementAndGet()
            persistMetrics()
            return false
        }

        return try {
            val cached = c.getCachedSpans(url)?.let { it.isNotEmpty() } ?: false
            if (cached) {
                cacheHits.incrementAndGet()
                cacheTimestamps[url] = System.currentTimeMillis()
                trackAccess(url)
            } else {
                cacheMisses.incrementAndGet()
            }
            persistMetrics()
            cached
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check if cached: $url", e)
            cacheMisses.incrementAndGet()
            persistMetrics()
            false
        }
    }

    fun pinResource(url: String) {
        pinned[url] = System.currentTimeMillis()
        Log.d(TAG, "Pinned resource: $url")
    }

    fun unpinResource(url: String) {
        pinned.remove(url)
        Log.d(TAG, "Unpinned resource: $url")
    }

    fun isPinned(url: String): Boolean = pinned.containsKey(url)

    fun getPinnedUrls(): List<String> = pinned.keys.toList()

    fun configureTTL(ttlMs: Long) {
        cacheTtlMs = ttlMs.coerceAtLeast(0)
        Log.d(TAG, "Cache TTL set to ${cacheTtlMs}ms")
    }

    fun setWifiOnlyMode(enabled: Boolean) {
        wifiOnlyMode = enabled
        Log.d(TAG, "WiFi-only mode: $enabled")
    }

    fun isWifiConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(network) ?: return false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                val activeNetwork = cm.activeNetworkInfo
                activeNetwork?.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check WiFi status", e)
            false
        }
    }

    fun shouldCache(context: Context): Boolean {
        return !wifiOnlyMode || isWifiConnected(context)
    }

    private fun scheduleExpiredCleanup() {
        backgroundExecutor.execute {
            try {
                Thread.sleep(5000)
                cleanupExpiredItems()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun cleanupExpiredItems(): Int {
        if (cacheTtlMs <= 0) return 0

        val c = cache ?: return 0
        val now = System.currentTimeMillis()
        var removed = 0

        try {
            val urls = c.keys.toList()

            urls.forEach { url ->
                if (pinned.containsKey(url)) {
                    return@forEach
                }

                val timestamp = cacheTimestamps[url] ?: run {
                    cacheTimestamps[url] = now
                    return@forEach
                }

                val age = now - timestamp
                if (age > cacheTtlMs) {
                    try {
                        c.removeResource(url)
                        cacheTimestamps.remove(url)
                        removed++
                        Log.d(TAG, "Removed expired: $url (age: ${age}ms)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove expired: $url", e)
                    }
                }
            }

            if (removed > 0) {
                Log.d(TAG, "Cleaned up $removed expired items")
                listeners.forEach { it.onExpiredItemsRemoved(removed) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup expired items", e)
        }

        return removed
    }

    fun warmCache(urls: List<String>, context: Context, callback: ((success: Int, failed: Int) -> Unit)? = null) {
        if (!shouldCache(context)) {
            Log.w(TAG, "Cache warming skipped: WiFi-only mode and not on WiFi")
            callback?.invoke(0, urls.size)
            return
        }

        backgroundExecutor.execute {
            var success = 0
            var failed = 0

            urls.forEach { url ->
                try {
                    if (!isCached(url)) {
                        cacheTimestamps[url] = System.currentTimeMillis()
                        success++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to warm cache for: $url", e)
                    failed++
                }
            }

            Log.d(TAG, "Cache warming complete: $success success, $failed failed")
            callback?.invoke(success, failed)
        }
    }

    fun getCachedUrls(): List<String> {
        val c = cache ?: return emptyList()
        return try {
            c.keys.toList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get cached URLs", e)
            emptyList()
        }
    }

    fun resetMetrics() {
        cacheHits.set(0)
        cacheMisses.set(0)
        persistMetrics()
        Log.d(TAG, "Cache metrics reset")
    }

    fun onLowMemory() {
        Log.w(TAG, "Low memory callback received")
        backgroundExecutor.execute {
            try {
                val unpinnedUrls = getCachedUrls().filter { !isPinned(it) }
                val toRemove = unpinnedUrls.take(unpinnedUrls.size / 4)

                toRemove.forEach { url ->
                    removeResource(url)
                }

                Log.d(TAG, "Low memory cleanup: removed ${toRemove.size} items")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle low memory", e)
            }
        }
    }

    fun enableIntegrityCheck(enabled: Boolean) {
        enableIntegrityCheck = enabled
        Log.d(TAG, "Integrity check: $enabled")
    }

    fun checkIntegrity(callback: ((IntegrityReport) -> Unit)? = null) {
        backgroundExecutor.execute {
            try {
                val c = cache ?: run {
                    callback?.invoke(IntegrityReport(0, 0, 0, 0, 0, emptyList()))
                    return@execute
                }

                val urls = c.keys.toList()
                var validCount = 0
                var corruptedCount = 0
                var missingCount = 0
                var totalSize = 0L
                val corrupted = mutableListOf<String>()

                urls.forEach { url ->
                    try {
                        val spans = c.getCachedSpans(url)
                        if (spans.isNullOrEmpty()) {
                            missingCount++
                        } else {
                            val size = spans.sumOf { it.length }
                            if (size > 0) {
                                validCount++
                                totalSize += size
                            } else {
                                corruptedCount++
                                corrupted.add(url)
                                corruptedFiles.add(url)
                            }
                        }
                    } catch (e: Exception) {
                        corruptedCount++
                        corrupted.add(url)
                        corruptedFiles.add(url)
                        Log.w(TAG, "Integrity check failed for: $url", e)
                    }
                }

                val report = IntegrityReport(
                    totalFiles = urls.size,
                    validFiles = validCount,
                    corruptedFiles = corruptedCount,
                    missingFiles = missingCount,
                    totalSize = totalSize,
                    corruptedUrls = corrupted
                )

                Log.d(TAG, "Integrity check: ${report.validFiles}/${report.totalFiles} valid, ${report.corruptedFiles} corrupted")
                callback?.invoke(report)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check integrity", e)
                callback?.invoke(IntegrityReport(0, 0, 0, 0, 0, emptyList()))
            }
        }
    }

    fun repairCache(callback: ((Int) -> Unit)? = null) {
        backgroundExecutor.execute {
            try {
                var repaired = 0

                corruptedFiles.toList().forEach { url ->
                    try {
                        cache?.removeResource(url)
                        corruptedFiles.remove(url)
                        cacheTimestamps.remove(url)
                        repaired++
                        Log.d(TAG, "Repaired (removed): $url")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to repair: $url", e)
                    }
                }

                Log.d(TAG, "Cache repair complete: $repaired items repaired")
                callback?.invoke(repaired)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to repair cache", e)
                callback?.invoke(0)
            }
        }
    }

    private fun trackAccess(url: String) {
        val now = System.currentTimeMillis()
        val pattern = accessPatterns.getOrPut(url) {
            AccessPattern(firstAccess = now, lastAccess = now)
        }

        pattern.accessCount++
        val interval = now - pattern.lastAccess
        pattern.avgAccessInterval = if (pattern.accessCount > 1) {
            (pattern.avgAccessInterval * (pattern.accessCount - 1) + interval) / pattern.accessCount
        } else {
            interval
        }
        pattern.lastAccess = now
    }

    fun getAnalytics(): CacheAnalytics {
        val totalRequests = cacheHits.get() + cacheMisses.get()
        val hitRate = if (totalRequests > 0) {
            (cacheHits.get() * 100.0 / totalRequests)
        } else 0.0

        val topUrls = accessPatterns.entries
            .sortedByDescending { it.value.accessCount }
            .take(10)
            .map { it.key to it.value.accessCount }

        val now = System.currentTimeMillis()
        val windowStart = now - ANALYTICS_WINDOW_MS

        val timeDistribution = accessPatterns.values
            .filter { it.lastAccess > windowStart }
            .groupBy { pattern ->
                val hourOfDay = java.util.Calendar.getInstance().apply {
                    timeInMillis = pattern.lastAccess
                }.get(java.util.Calendar.HOUR_OF_DAY)
                when (hourOfDay) {
                    in 0..5 -> "Night (0-6)"
                    in 6..11 -> "Morning (6-12)"
                    in 12..17 -> "Afternoon (12-18)"
                    else -> "Evening (18-24)"
                }
            }
            .mapValues { it.value.size }

        val stats = getStats()
        val totalBytes = (stats?.get("totalBytes") as? Long) ?: 0L
        val configuredBytes = (stats?.get("configuredLimitBytes") as? Long) ?: 1L
        val storageEfficiency = if (configuredBytes > 0) {
            (totalBytes.toDouble() / configuredBytes) * 100
        } else 0.0

        val recommendations = buildRecommendations(hitRate, storageEfficiency, totalRequests)

        return CacheAnalytics(
            totalRequests = totalRequests,
            hitRate = hitRate,
            avgResponseTime = 0L,
            topUrls = topUrls,
            timeDistribution = timeDistribution,
            storageEfficiency = storageEfficiency,
            recommendations = recommendations
        )
    }

    private fun buildRecommendations(
        hitRate: Double,
        storageEfficiency: Double,
        totalRequests: Long
    ): List<String> {
        val recommendations = mutableListOf<String>()

        if (hitRate < 50 && totalRequests > 100) {
            recommendations.add("Low hit rate (${"%.1f".format(hitRate)}%). Consider increasing cache size.")
        }

        if (storageEfficiency < 30) {
            recommendations.add("Cache underutilized (${"%.1f".format(storageEfficiency)}%). Consider reducing cache size.")
        } else if (storageEfficiency > 95) {
            recommendations.add("Cache almost full (${"%.1f".format(storageEfficiency)}%). Consider increasing cache size or reducing TTL.")
        }

        val pinnedCount = pinned.size
        if (pinnedCount > 100) {
            recommendations.add("Too many pinned items ($pinnedCount). Consider unpinning less important content.")
        }

        val expiredCount = cacheTimestamps.values.count {
            System.currentTimeMillis() - it > cacheTtlMs
        }
        if (expiredCount > 10) {
            recommendations.add("$expiredCount expired items found. Run cleanupExpiredItems().")
        }

        if (corruptedFiles.size > 0) {
            recommendations.add("${corruptedFiles.size} corrupted files detected. Run repairCache().")
        }

        return recommendations
    }

    fun exportCacheManifest(): String {
        val urls = getCachedUrls()
        val manifest = buildString {
            appendLine("# Cache Manifest")
            appendLine("# Generated: ${java.util.Date()}")
            appendLine("# Total items: ${urls.size}")
            appendLine()

            urls.forEach { url ->
                val size = getCachedBytes(url)
                val pinned = if (isPinned(url)) "[PINNED]" else ""
                val priority = urlPriority[url]?.name ?: "NORMAL"
                val timestamp = cacheTimestamps[url] ?: 0L
                val pattern = accessPatterns[url]

                appendLine("URL: $url")
                appendLine("  Size: $size bytes")
                appendLine("  Priority: $priority")
                appendLine("  Timestamp: $timestamp")
                if (pattern != null) {
                    appendLine("  Access Count: ${pattern.accessCount}")
                    appendLine("  Last Access: ${pattern.lastAccess}")
                }
                appendLine("  $pinned")
                appendLine()
            }
        }

        return manifest
    }

    fun setPriority(url: String, priority: UrlPriority) {
        urlPriority[url] = priority
        Log.d(TAG, "Set priority for $url: $priority")
    }

    fun getPriority(url: String): UrlPriority {
        return urlPriority[url] ?: UrlPriority.NORMAL
    }

    fun enablePrefetchPrediction(enabled: Boolean) {
        enablePrefetchPrediction = enabled
        Log.d(TAG, "Prefetch prediction: $enabled")
    }

    fun enableRequestTracing(enabled: Boolean) {
        enableRequestTracing = enabled
        if (!enabled) {
            requestTraces.clear()
        }
        Log.d(TAG, "Request tracing: $enabled")
    }

    fun traceRequest(url: String, source: String = "unknown") {
        if (!enableRequestTracing) return

        val startTime = System.currentTimeMillis()
        val hit = isCached(url)
        val duration = System.currentTimeMillis() - startTime

        val trace = RequestTrace(
            timestamp = System.currentTimeMillis(),
            url = url,
            source = source,
            hit = hit,
            durationMs = duration
        )

        requestTraces.getOrPut(url) { mutableListOf() }.add(trace)

        if (requestTraces[url]!!.size > 100) {
            requestTraces[url] = requestTraces[url]!!.takeLast(50).toMutableList()
        }
    }

    fun getRequestTraces(url: String): List<RequestTrace> {
        return requestTraces[url]?.toList() ?: emptyList()
    }

    fun getAllTraces(): Map<String, List<RequestTrace>> {
        return requestTraces.mapValues { it.value.toList() }
    }

    fun predictNextPrefetch(currentUrl: String, context: List<String>): List<PrefetchPrediction> {
        if (!enablePrefetchPrediction) return emptyList()

        val predictions = mutableListOf<PrefetchPrediction>()

        context.forEach { url ->
            var score = 0.0
            val reasons = mutableListOf<String>()

            val pattern = accessPatterns[url]
            if (pattern != null) {
                val recencyScore = calculateRecencyScore(pattern.lastAccess)
                val frequencyScore = calculateFrequencyScore(pattern.accessCount)
                val intervalScore = calculateIntervalScore(pattern.avgAccessInterval)

                score += recencyScore * 0.3
                score += frequencyScore * 0.4
                score += intervalScore * 0.3

                if (recencyScore > 0.7) reasons.add("recently accessed")
                if (frequencyScore > 0.7) reasons.add("frequently accessed")
                if (intervalScore > 0.7) reasons.add("regular access pattern")
            }

            val priority = urlPriority[url]
            if (priority != null) {
                score *= priority.weight
                reasons.add("priority: ${priority.name}")
            }

            if (isPinned(url)) {
                score *= 1.2
                reasons.add("pinned")
            }

            if (!isCached(url)) {
                score *= 1.5
                reasons.add("not cached")
            }

            prefetchScores[url] = score

            if (score > 0.3) {
                predictions.add(
                    PrefetchPrediction(
                        url = url,
                        score = score,
                        reason = reasons.joinToString(", "),
                        confidence = calculateConfidence(pattern)
                    )
                )
            }
        }

        return predictions.sortedByDescending { it.score }
    }

    private fun calculateRecencyScore(lastAccess: Long): Double {
        val age = System.currentTimeMillis() - lastAccess
        val maxAge = 7 * 24 * 60 * 60 * 1000L // 7 days

        return (1.0 - (age.toDouble() / maxAge)).coerceIn(0.0, 1.0)
    }

    private fun calculateFrequencyScore(accessCount: Int): Double {
        return (accessCount.toDouble() / 100).coerceIn(0.0, 1.0)
    }

    private fun calculateIntervalScore(avgInterval: Long): Double {
        if (avgInterval == 0L) return 0.0

        val idealInterval = 24 * 60 * 60 * 1000L // 1 day
        val deviation = kotlin.math.abs(avgInterval - idealInterval).toDouble()
        val score = 1.0 - (deviation / idealInterval)

        return score.coerceIn(0.0, 1.0)
    }

    private fun calculateConfidence(pattern: AccessPattern?): Double {
        if (pattern == null) return 0.5

        val dataPoints = pattern.accessCount
        return (dataPoints.toDouble() / 20).coerceIn(0.0, 1.0)
    }

    fun smartEviction(bytesToFree: Long): Int {
        val urls = getCachedUrls()
        var freed = 0L
        var evicted = 0

        val scoredUrls = urls.map { url ->
            val priority = urlPriority[url] ?: UrlPriority.NORMAL
            val pattern = accessPatterns[url]
            val size = getCachedBytes(url)

            val recencyScore = pattern?.let { calculateRecencyScore(it.lastAccess) } ?: 0.5
            val frequencyScore = pattern?.let { calculateFrequencyScore(it.accessCount) } ?: 0.5

            val evictionScore = (1.0 - recencyScore * 0.5 - frequencyScore * 0.5) * (1.0 - priority.weight)

            Triple(url, evictionScore, size)
        }.sortedByDescending { it.second }

        for ((url, score, size) in scoredUrls) {
            if (freed >= bytesToFree) break

            if (isPinned(url)) continue
            if (urlPriority[url] == UrlPriority.CRITICAL) continue

            if (removeResource(url)) {
                freed += size
                evicted++
                Log.d(TAG, "Evicted $url (score: ${"%.3f".format(score)}, size: $size)")
            }
        }

        Log.d(TAG, "Smart eviction: freed $freed bytes, evicted $evicted items")
        return evicted
    }

    fun getCachedBytes(url: String): Long {
        val c = cache ?: return 0L
        return try {
            c.getCachedSpans(url)?.sumOf { it.length } ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get cached bytes for: $url", e)
            0L
        }
    }

    fun release() {
        synchronized(this) {
            if (state == CacheState.RELEASED) {
                Log.w(TAG, "Cache already released")
                return
            }

            val previousState = state
            state = CacheState.RELEASED

            cache?.let { c ->
                try {
                    Log.d(TAG, "Releasing cache (previous state: $previousState)...")
                    c.release()
                    Log.d(TAG, "Cache released successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release cache", e)
                } finally {
                    cache = null
                }
            }

            databaseProvider?.let { provider ->
                try {
                    (provider as? StandaloneDatabaseProvider)?.close()
                    Log.d(TAG, "Database provider closed")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to close database provider", e)
                } finally {
                    databaseProvider = null
                }
            }

            try {
                backgroundExecutor.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to shutdown background executor", e)
            }

            listeners.clear()
            resetMetrics()

            initializationFailed = false
            state = CacheState.UNINITIALIZED
        }
    }

    private fun cleanupResources() {
        cache = null

        databaseProvider?.let { provider ->
            try {
                (provider as? StandaloneDatabaseProvider)?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error during cleanup", e)
            }
        }
        databaseProvider = null
    }
}
