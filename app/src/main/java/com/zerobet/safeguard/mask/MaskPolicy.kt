package com.zerobet.safeguard.mask

import java.io.File
import java.io.IOException

/** Aspetto di un pannello oscurante. */
enum class MaskLayout {
    /** Pannello scuro con scudo e scritta (nessuna foto del tutore o funzione spenta). */
    DEFAULT_DARK,
    /** Banner o box grande: foto intera e nitida su sfondo sfumato della stessa foto, didascalia in basso. */
    FULL_BANNER,
    /** Striscia sottile (riga di una email, banner orizzontale): miniatura a sinistra, frase a destra. */
    COMPACT_STRIP,
}

/** Regole del pannello con le foto del tutore. Kotlin puro, coperto da unit test. */
object MaskPolicy {
    const val FULL_BANNER_MIN_DP = 100f

    fun layoutFor(heightPx: Int, density: Float, hasPhotos: Boolean, enabled: Boolean): MaskLayout = when {
        !enabled || !hasPhotos -> MaskLayout.DEFAULT_DARK
        heightPx >= FULL_BANNER_MIN_DP * density -> MaskLayout.FULL_BANNER
        else -> MaskLayout.COMPACT_STRIP
    }
}

/**
 * Rotazione delle foto tra i pannelli. Ogni pannello (slot) riceve una foto quando compare e la
 * tiene finché la schermata non cambia: durante lo scorrimento l'immagine non lampeggia. A ogni
 * nuova schermata la rotazione prosegue da dove era rimasta, così le foto si alternano.
 */
class PhotoRotation {
    private val assigned = HashMap<Int, Int>()
    private var next = 0

    fun photoFor(slot: Int, count: Int): Int {
        if (count <= 0) return -1
        val current = assigned[slot]
        if (current != null && current < count) return current
        val photo = next % count
        next = (next + 1) % count
        assigned[slot] = photo
        return photo
    }

    /** Nuova schermata: i pannelli successivi riceveranno nuove foto. */
    fun reset() {
        assigned.clear()
    }
}

/** Calcoli sulle immagini (dimensioni, campionamento, rotazione EXIF, ritaglio). Kotlin puro. */
object MaskImageMath {

    data class Size(val width: Int, val height: Int)
    data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    /** Lato lungo al massimo [maxSide], proporzioni invariate (mai ingrandire). */
    fun targetSize(width: Int, height: Int, maxSide: Int): Size {
        val long = maxOf(width, height)
        if (long <= maxSide || long <= 0) return Size(width, height)
        val scale = maxSide.toDouble() / long
        return Size(maxOf(1, Math.round(width * scale).toInt()), maxOf(1, Math.round(height * scale).toInt()))
    }

    /** Potenza di 2 più grande che lascia il lato lungo decodificato >= [maxSide] (risparmio di memoria). */
    fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val long = maxOf(width, height)
        while (long / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** Gradi di rotazione per l'orientamento EXIF (1, 3, 6, 8; gli specchiati sono trattati come rotazioni). */
    fun exifDegrees(orientation: Int): Int = when (orientation) {
        3, 4 -> 180
        6, 5 -> 90
        8, 7 -> 270
        else -> 0
    }

    /**
     * Ritaglio con proporzioni [targetAspect] (larghezza/altezza) che privilegia la parte alta della
     * foto ([verticalBias] 0 = in alto, 0.5 = centro): nei ritratti i volti stanno in alto, un
     * ritaglio centrale taglierebbe le teste.
     */
    fun biasedCrop(width: Int, height: Int, targetAspect: Float, verticalBias: Float = 0.3f): Crop {
        if (width <= 0 || height <= 0 || targetAspect <= 0f) return Crop(0, 0, maxOf(width, 0), maxOf(height, 0))
        val sourceAspect = width.toFloat() / height
        return if (sourceAspect > targetAspect) {
            val w = maxOf(1, Math.round(height * targetAspect))
            Crop((width - w) / 2, 0, w, height)
        } else {
            val h = maxOf(1, Math.round(width / targetAspect))
            val top = Math.round((height - h) * verticalBias.coerceIn(0f, 1f))
            Crop(0, top, width, h)
        }
    }
}

/**
 * File delle foto del tutore nello storage protetto dal dispositivo (Direct Boot): copie locali
 * indipendenti dalla galleria. Kotlin puro (java.io), coperto da unit test.
 */
class MaskImageFiles(private val dir: File, val maxImages: Int = MAX_IMAGES) {

    fun list(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: emptyArray()).sortedBy { it.name }

    fun count(): Int = list().size

    fun remaining(): Int = (maxImages - count()).coerceAtLeast(0)

    /** Nuovo file vuoto in cui scrivere una foto (null se il limite è raggiunto). */
    fun newFile(now: Long, index: Int): File? {
        if (remaining() <= 0) return null
        dir.mkdirs()
        var n = index
        while (true) {
            val f = File(dir, "%013d_%02d%s".format(now, n, EXTENSION))
            if (!f.exists()) return f
            n++
        }
    }

    /** Salva i byte di una foto già compressa con scrittura atomica (file temporaneo + rename). */
    fun write(now: Long, index: Int, bytes: ByteArray): File? {
        val target = newFile(now, index) ?: return null
        val tmp = File(dir, target.name + ".tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) throw IOException("rename")
            target
        } catch (e: IOException) {
            tmp.delete()
            null
        }
    }

    /** Rotazione FIFO: se lo store è pieno toglie le foto più vecchie. @return i nomi rimossi. */
    fun makeRoom(): List<String> {
        val removed = ArrayList<String>()
        val all = list()
        var excess = all.size - maxImages + 1
        for (f in all) {
            if (excess <= 0) break
            if (f.delete()) removed += f.name
            excess--
        }
        return removed
    }

    fun delete(name: String): Boolean {
        if (name.contains('/') || name.contains('\\') || !name.endsWith(EXTENSION)) return false
        return File(dir, name).delete()
    }

    fun clear() {
        list().forEach { it.delete() }
    }

    companion object {
        const val MAX_IMAGES = 10
        const val MAX_SIDE = 800
        const val EXTENSION = ".jpg"
    }
}
