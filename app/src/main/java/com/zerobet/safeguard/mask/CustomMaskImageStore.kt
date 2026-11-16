package com.zerobet.safeguard.mask

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.zerobet.safeguard.security.DeviceStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Foto del tutore da mostrare sui pannelli oscuranti ("ancoraggio affettivo").
 *
 * Le foto scelte nella galleria vengono COPIATE (non si tengono gli Uri, che perderebbero il
 * permesso al riavvio o sparirebbero se la foto venisse cancellata): decodifica campionata,
 * rotazione secondo l'EXIF, lato lungo massimo 800 px, JPEG in
 * `deviceProtectedStorage/files/mask_images/`, leggibile anche a schermo bloccato.
 *
 * In memoria: cache LRU di bitmap pronte (RGB_565, al massimo 4 MB) per disegnare i pannelli senza
 * leggere il disco durante lo scorrimento; le foto vengono precaricate in background.
 */
class CustomMaskImageStore(context: Context) {

    data class Settings(val enabled: Boolean, val caption: String, val images: List<String>) {
        val active: Boolean get() = enabled && images.isNotEmpty()
    }

    private val appContext = context.applicationContext
    private val files = MaskImageFiles(File(DeviceStorage.context(context).filesDir, DIR))
    private val prefs get() = DeviceStorage.statePrefs(appContext)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "cutbet-mask-images").apply { isDaemon = true } }

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private val _settings = MutableStateFlow(readSettings())
    /** Impostazioni correnti; cambiano quando il tutore aggiunge/rimuove foto o modifica la frase. */
    val settings: StateFlow<Settings> = _settings

    private fun readSettings() = Settings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        caption = prefs.getString(KEY_CAPTION, "").orEmpty(),
        images = files.list().map { it.name },
    )

    private fun refresh() {
        _settings.value = readSettings()
    }

    val remaining: Int get() = files.remaining()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        refresh()
    }

    fun setCaption(caption: String) {
        prefs.edit().putString(KEY_CAPTION, caption.take(MAX_CAPTION)).apply()
        refresh()
    }

    /** Copia le foto scelte (thread di I/O). @return quante sono state salvate. */
    fun import(uris: List<Uri>): Int {
        var saved = 0
        val now = System.currentTimeMillis()
        uris.forEachIndexed { i, uri ->
            if (files.remaining() <= 0) return@forEachIndexed
            val bytes = try {
                prepare(uri)
            } catch (e: Exception) {
                Log.w(TAG, "Foto non importata: ${e.javaClass.simpleName}")
                null
            } ?: return@forEachIndexed
            if (files.write(now, i, bytes) != null) saved++
        }
        refresh()
        preload()
        return saved
    }

    fun remove(name: String) {
        files.delete(name)
        cache.remove(name)
        cache.remove(THUMB + name)
        cache.remove(BLUR + name)
        refresh()
    }

    /** Decodifica campionata, rotazione EXIF, lato lungo 800 px, JPEG 85. */
    private fun prepare(uri: Uri): ByteArray? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = MaskImageMath.sampleSizeFor(bounds.outWidth, bounds.outHeight, MaskImageFiles.MAX_SIDE)
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val orientation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } ?: 1
        } catch (e: Exception) {
            1
        }
        val degrees = MaskImageMath.exifDegrees(orientation)
        val target = MaskImageMath.targetSize(bitmap.width, bitmap.height, MaskImageFiles.MAX_SIDE)
        if (degrees != 0 || target.width != bitmap.width) {
            val matrix = Matrix().apply {
                postScale(target.width.toFloat() / bitmap.width, target.height.toFloat() / bitmap.height)
                postRotate(degrees.toFloat())
            }
            val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (transformed !== bitmap) bitmap.recycle()
            bitmap = transformed
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bitmap per i pannelli
    // ---------------------------------------------------------------------------------------

    /** Foto pronta da mostrare (dalla cache; dal disco solo la prima volta). */
    fun bitmap(name: String): Bitmap? {
        cache.get(name)?.let { return it }
        val file = File(DeviceStorage.context(appContext).filesDir, "$DIR/$name")
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: return null
        cache.put(name, bitmap)
        return bitmap
    }

    /** Miniatura quadrata con ritaglio spostato verso l'alto (volti interi). */
    fun thumbnail(name: String, sizePx: Int): Bitmap? {
        val key = "$THUMB$name"
        cache.get(key)?.takeIf { it.width >= sizePx }?.let { return it }
        val source = bitmap(name) ?: return null
        val crop = MaskImageMath.biasedCrop(source.width, source.height, 1f)
        val side = sizePx.coerceIn(16, 512)
        val square = Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height)
        val scaled = Bitmap.createScaledBitmap(square, side, side, true)
        if (square !== scaled && square !== source) square.recycle()
        cache.put(key, scaled)
        return scaled
    }

    /** Versione piccolissima della foto: ingrandita fa da sfondo sfumato. */
    fun blurred(name: String): Bitmap? {
        val key = "$BLUR$name"
        cache.get(key)?.let { return it }
        val source = bitmap(name) ?: return null
        val size = MaskImageMath.targetSize(source.width, source.height, BLUR_SIDE)
        val small = Bitmap.createScaledBitmap(source, size.width, size.height, true)
        cache.put(key, small)
        return small
    }

    /** Precarica in background le foto (e le versioni sfumate) per i pannelli. */
    fun preload() {
        val names = _settings.value.images
        io.execute {
            names.take(MAX_PRELOAD).forEach { name ->
                try {
                    bitmap(name)
                    blurred(name)
                } catch (e: Exception) {
                    Log.w(TAG, "Precaricamento fallito: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "CutBetMaskImages"
        private const val DIR = "mask_images"
        private const val KEY_ENABLED = "mask_images_enabled"
        private const val KEY_CAPTION = "mask_images_caption"
        private const val THUMB = "thumb:"
        private const val BLUR = "blur:"
        private const val CACHE_BYTES = 4 * 1024 * 1024
        private const val JPEG_QUALITY = 85
        private const val BLUR_SIDE = 24
        private const val MAX_PRELOAD = 4
        const val MAX_CAPTION = 80
    }
}
