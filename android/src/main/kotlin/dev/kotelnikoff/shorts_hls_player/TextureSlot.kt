package dev.kotelnikoff.shorts_hls_player

import android.view.Surface
import io.flutter.view.TextureRegistry

class TextureSlot(
    private val textures: TextureRegistry
) {
    private var entry: TextureRegistry.SurfaceTextureEntry? = null
    private var surface: Surface? = null

    /** id текстуры для передачи на сторону Flutter (Texture widget) */
    val id: Long get() = entry?.id() ?: -1L

    /** создана ли текстура (и surface) */
    val isCreated: Boolean get() = entry != null && surface != null

    /**
     * Создать (или пересоздать) Texture с указанным размером буфера.
     * Возвращает id для Flutter-side Texture.
     */
    @Synchronized
    fun create(width: Int = 360, height: Int = 640): Long {
        // Если уже есть — освободим и создадим заново (чтобы гарантировать корректный state)
        release()

        val e = textures.createSurfaceTexture()
        entry = e

        val st = e.surfaceTexture()
        // Важно: задать буфер до создания Surface
        st.setDefaultBufferSize(width, height)

        surface = Surface(st)
        return e.id()
    }

    /**
     * Обновить default buffer size без пересоздания Texture/Surface.
     * Полезно, если после первых кадров стали известны реальные размеры видео.
     */
    @Synchronized
    fun updateBufferSize(width: Int, height: Int) {
        entry?.surfaceTexture()?.setDefaultBufferSize(width, height)
        // Surface можно не пересоздавать — он привязан к тому же SurfaceTexture
    }

    /** Получить текущий Surface для передачи в ExoPlayer */
    @Synchronized
    fun getSurface(): Surface? = surface

    /** Освободить все ресурсы (Surface + TextureEntry) */
    @Synchronized
    fun release() {
        try {
            surface?.release()
        } catch (_: Throwable) { /* ignore */ }
        surface = null

        try {
            entry?.release()
        } catch (_: Throwable) { /* ignore */ }
        entry = null
    }
}