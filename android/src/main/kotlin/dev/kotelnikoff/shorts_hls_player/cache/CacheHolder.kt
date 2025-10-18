package dev.kotelnikoff.shorts_hls_player.cache

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

internal object CacheHolder {
    private const val CACHE_DIR = "shorts_media_cache"
    private const val CACHE_LIMIT_BYTES = 256L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null
    @Volatile
    private var databaseProvider: DatabaseProvider? = null

    fun obtain(context: Context): SimpleCache {
        val existing = cache
        if (existing != null) return existing
        synchronized(this) {
            val again = cache
            if (again != null) return again
            val dir = File(context.cacheDir, CACHE_DIR)
            if (!dir.exists()) dir.mkdirs()
            val provider = databaseProvider ?: StandaloneDatabaseProvider(context).also { databaseProvider = it }
            val evictor = LeastRecentlyUsedCacheEvictor(CACHE_LIMIT_BYTES)
            val created = SimpleCache(dir, evictor, provider)
            cache = created
            return created
        }
    }

    fun release() {
        synchronized(this) {
            cache?.release()
            cache = null
        }
    }
}
