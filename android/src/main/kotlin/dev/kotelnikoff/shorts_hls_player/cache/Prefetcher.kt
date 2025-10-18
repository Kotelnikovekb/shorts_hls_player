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
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val buffer = ByteArray(32 * 1024)
    @Volatile
    private var bytesLimit: Long = limitBytes.coerceAtLeast(0L)

    private class StopPrefetch : RuntimeException()

    fun updateLimit(limit: Long) {
        bytesLimit = limit.coerceAtLeast(0L)
    }

    fun prefetch(url: String) {
        if (url.isEmpty()) return
        if (!inFlight.add(url)) return
        executor.execute {
            try {
                val limit = bytesLimit
                val spec = DataSpec.Builder()
                    .setUri(url)
                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                    .build()
                val progress = CacheWriter.ProgressListener { _, bytesCached, _ ->
                    if (limit > 0 && bytesCached >= limit) throw StopPrefetch()
                }
                CacheWriter(dataSourceFactory.createDataSource(), spec, buffer, progress).cache()
            } catch (_: StopPrefetch) {
            } catch (_: Throwable) {
            } finally {
                inFlight.remove(url)
            }
        }
    }

    fun shutdown() {
        inFlight.clear()
        executor.shutdownNow()
    }
}
