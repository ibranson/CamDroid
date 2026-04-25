package app.camdroid.review

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath

/**
 * Application class — single instance for the lifetime of the process.
 *
 * Configures Coil's singleton ImageLoader so thumbnails and previews are
 * cached on disk between app launches. With our HTTP responses' aggressive
 * Cache-Control: max-age=31536000, immutable headers (set on the Pi side),
 * Coil's disk cache + OkHttp's caching combine into "fetch each image once,
 * never again" — supporting offline scroll-back through past sessions.
 */
class CamDroidApp : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024) // 512 MB; ~25k thumbs or ~50 fulls
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
