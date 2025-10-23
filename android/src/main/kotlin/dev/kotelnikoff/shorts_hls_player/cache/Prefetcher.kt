package dev.kotelnikoff.shorts_hls_player.cache

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class Prefetcher(
    private val cache: SimpleCache,
    private val dataSourceFactory: CacheDataSource.Factory,
    limitBytes: Long
) {
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private val inFlight = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val buffer = ByteArray(64 * 1024)
    @Volatile
    private var bytesLimit: Long = limitBytes.coerceAtLeast(0L)
    @Volatile
    private var isShutdown = false

    private class StopPrefetch : RuntimeException()

    fun updateLimit(limit: Long) {
        bytesLimit = limit.coerceAtLeast(0L)
    }

    fun prefetch(url: String) {
        if (url.isEmpty() || isShutdown) return
        if (!inFlight.add(url)) return
        executor.execute {
            if (isShutdown) {
                inFlight.remove(url)
                return@execute
            }
            try {
                val limit = bytesLimit
                val spec = DataSpec.Builder()
                    .setUri(url)
                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                    .build()
                val progress = CacheWriter.ProgressListener { _, bytesCached, _ ->
                    if (isShutdown || (limit > 0 && bytesCached >= limit)) throw StopPrefetch()
                }
                CacheWriter(dataSourceFactory.createDataSource(), spec, buffer, progress).cache()
            } catch (_: StopPrefetch) {
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Throwable) {
            } finally {
                inFlight.remove(url)
            }
        }
    }

    fun shutdown() {
        isShutdown = true
        inFlight.clear()
        executor.shutdownNow()
    }
}
