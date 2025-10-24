package dev.kotelnikoff.shorts_hls_player

import android.os.Looper
import android.view.Surface
import io.flutter.view.TextureRegistry
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@JvmInline
value class TextureId(val value: Long) {
    companion object {
        val INVALID = TextureId(-1L)
    }

    fun isValid(): Boolean = value != INVALID.value
}

@JvmInline
value class TextureWidth(val value: Int) {
    init {
        require(value > 0) { "Width must be positive" }
    }
}

@JvmInline
value class TextureHeight(val value: Int) {
    init {
        require(value > 0) { "Height must be positive" }
    }
}

data class TextureDimensions(
    val width: TextureWidth,
    val height: TextureHeight
) {
    companion object {
        val DEFAULT = TextureDimensions(
            width = TextureWidth(360),
            height = TextureHeight(640)
        )

        val HD = TextureDimensions(
            width = TextureWidth(720),
            height = TextureHeight(1280)
        )

        val FULL_HD = TextureDimensions(
            width = TextureWidth(1080),
            height = TextureHeight(1920)
        )
    }
}

sealed interface TextureState {
    data object Idle : TextureState
    data class Created(val textureId: TextureId, val surface: Surface) : TextureState
    data class Error(val throwable: Throwable) : TextureState

    fun isIdle(): Boolean = this is Idle
    fun isCreated(): Boolean = this is Created
    fun isError(): Boolean = this is Error
}

class TextureSlot(
    private val textures: TextureRegistry
) {
    private val lock = ReentrantLock()
    private var entry: TextureRegistry.SurfaceTextureEntry? = null
    private var surface: Surface? = null
    private var state: TextureState = TextureState.Idle

    val textureId: TextureId
        get() = lock.withLock {
            (state as? TextureState.Created)?.textureId ?: TextureId.INVALID
        }

    val isCreated: Boolean
        get() = lock.withLock { state.isCreated() }

    fun create(dimensions: TextureDimensions = TextureDimensions.DEFAULT): TextureId = lock.withLock {
        ensureMainThread("create")
        cleanupLocked()

        val textureEntry = textures.createSurfaceTexture()
        entry = textureEntry

        textureEntry.surfaceTexture().setDefaultBufferSize(
            dimensions.width.value,
            dimensions.height.value
        )

        val newSurface = Surface(textureEntry.surfaceTexture())
        surface = newSurface

        val id = TextureId(textureEntry.id())
        state = TextureState.Created(id, newSurface)
        id
    }

    fun updateBufferSize(dimensions: TextureDimensions) = lock.withLock {
        ensureMainThread("updateBufferSize")
        entry?.surfaceTexture()?.setDefaultBufferSize(
            dimensions.width.value,
            dimensions.height.value
        )
    }

    fun updateBufferSize(width: Int, height: Int) =
        updateBufferSize(TextureDimensions(TextureWidth(width), TextureHeight(height)))

    fun getSurface(): Surface? = lock.withLock { surface?.takeIf { it.isValid } }

    fun release() = lock.withLock {
        cleanupLocked()
        state = TextureState.Idle
    }

    private fun cleanupLocked() {
        surface?.let { s ->
            runCatching {
                if (s.isValid) s.release()
            }
        }
        surface = null

        entry?.let { e ->
            runCatching { e.release() }
        }
        entry = null
    }

    private fun ensureMainThread(action: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "TextureSlot.$action() must be called on the main thread"
        }
    }
}

fun TextureSlot(
    textures: TextureRegistry,
    block: TextureSlot.() -> Unit = {}
): TextureSlot = TextureSlot(textures).apply(block)

inline fun <T> TextureSlot.withSurface(block: (Surface) -> T): T {
    val surface = getSurface() ?: throw IllegalStateException("Surface not available")
    return block(surface)
}

fun TextureDimensions(width: Int, height: Int): TextureDimensions =
    TextureDimensions(TextureWidth(width), TextureHeight(height))
