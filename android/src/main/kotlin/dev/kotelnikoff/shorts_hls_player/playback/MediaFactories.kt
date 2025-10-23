package dev.kotelnikoff.shorts_hls_player.playback

import android.content.Context
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

internal object MediaFactories {
    fun dataSourceFactory(context: Context, cache: SimpleCache): CacheDataSource.Factory {
        val userAgent = Util.getUserAgent(context, "ShortsHlsPlayer")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
            .setKeepPostFor302Redirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept-Encoding" to "gzip, deflate"
                )
            )
        val upstream = DefaultDataSource.Factory(context, httpFactory)
        val cacheSinkFactory = CacheDataSink.Factory()
            .setCache(cache)
            .setFragmentSize(2 * 1024 * 1024) // 2MB вместо default 5MB
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(cacheSinkFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(null)
    }

    fun mediaSourceFactory(context: Context, cache: SimpleCache): DefaultMediaSourceFactory {
        val factory = dataSourceFactory(context, cache)
        return DefaultMediaSourceFactory(factory)
    }
}
