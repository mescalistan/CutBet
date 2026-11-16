package com.zerobet.safeguard

import com.zerobet.safeguard.mask.MaskImageFiles
import com.zerobet.safeguard.mask.MaskImageMath
import com.zerobet.safeguard.mask.MaskLayout
import com.zerobet.safeguard.mask.MaskPolicy
import com.zerobet.safeguard.mask.PhotoRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MaskImagesTest {

    private val density = 2.625f   // 420 dpi

    // ---------------------------------------------------------------------------------------
    // Scelta del layout
    // ---------------------------------------------------------------------------------------

    @Test
    fun `layout in base all'altezza e alla presenza di foto`() {
        val px = { dp: Int -> Math.round(dp * density) }
        assertEquals(MaskLayout.FULL_BANNER, MaskPolicy.layoutFor(px(350), density, hasPhotos = true, enabled = true))
        assertEquals(MaskLayout.FULL_BANNER, MaskPolicy.layoutFor(px(100), density, hasPhotos = true, enabled = true))
        assertEquals(MaskLayout.COMPACT_STRIP, MaskPolicy.layoutFor(px(99), density, hasPhotos = true, enabled = true))
        assertEquals(MaskLayout.COMPACT_STRIP, MaskPolicy.layoutFor(px(60), density, hasPhotos = true, enabled = true))
        // Nessuna foto o funzione spenta: il pannello scuro di sempre.
        assertEquals(MaskLayout.DEFAULT_DARK, MaskPolicy.layoutFor(px(350), density, hasPhotos = false, enabled = true))
        assertEquals(MaskLayout.DEFAULT_DARK, MaskPolicy.layoutFor(px(60), density, hasPhotos = true, enabled = false))
    }

    // ---------------------------------------------------------------------------------------
    // Rotazione delle foto
    // ---------------------------------------------------------------------------------------

    @Test
    fun `lo stesso pannello tiene la stessa foto durante lo scorrimento`() {
        val r = PhotoRotation()
        val first = r.photoFor(0, 3)
        val second = r.photoFor(1, 3)
        assertNotEquals(first, second)
        repeat(50) {
            assertEquals(first, r.photoFor(0, 3))
            assertEquals(second, r.photoFor(1, 3))
        }
    }

    @Test
    fun `a ogni schermata le foto si alternano`() {
        val r = PhotoRotation()
        val seen = HashSet<Int>()
        repeat(3) {
            seen += r.photoFor(0, 3)
            r.reset()
        }
        assertEquals(setOf(0, 1, 2), seen)
        // Foto rimosse: l'indice resta sempre valido.
        val r2 = PhotoRotation()
        r2.photoFor(0, 5); r2.photoFor(1, 5); r2.photoFor(2, 5)
        assertTrue(r2.photoFor(2, 2) in 0..1)
        assertEquals(-1, r2.photoFor(0, 0))
    }

    // ---------------------------------------------------------------------------------------
    // Dimensioni, campionamento, EXIF, ritaglio
    // ---------------------------------------------------------------------------------------

    @Test
    fun `ridimensionamento a 800 px sul lato lungo senza deformare`() {
        assertEquals(MaskImageMath.Size(800, 600), MaskImageMath.targetSize(4000, 3000, 800))
        assertEquals(MaskImageMath.Size(450, 800), MaskImageMath.targetSize(2160, 3840, 800))
        assertEquals(MaskImageMath.Size(640, 480), MaskImageMath.targetSize(640, 480, 800))   // mai ingrandire
        assertEquals(4, MaskImageMath.sampleSizeFor(4000, 3000, 800))   // 1000 px decodificati, poi 800
        assertEquals(1, MaskImageMath.sampleSizeFor(1000, 700, 800))
        assertEquals(0, MaskImageMath.exifDegrees(1))
        assertEquals(90, MaskImageMath.exifDegrees(6))
        assertEquals(180, MaskImageMath.exifDegrees(3))
        assertEquals(270, MaskImageMath.exifDegrees(8))
    }

    @Test
    fun `ritaglio quadrato di un ritratto privilegia la parte alta (volti)`() {
        val crop = MaskImageMath.biasedCrop(600, 800, 1f)
        assertEquals(600, crop.width)
        assertEquals(600, crop.height)
        assertEquals(60, crop.top)            // 30% dello spazio in più, non il centro (100)
        val wide = MaskImageMath.biasedCrop(800, 600, 1f)
        assertEquals(MaskImageMath.Crop(100, 0, 600, 600), wide)
    }

    // ---------------------------------------------------------------------------------------
    // File nello storage Direct Boot
    // ---------------------------------------------------------------------------------------

    @Test
    fun `salvataggio, elenco, limite di 10 e cancellazione`() {
        val dir = File(Files.createTempDirectory("maskimg").toFile(), "mask_images")
        val files = MaskImageFiles(dir)
        assertEquals(10, files.remaining())
        val saved = (0 until 12).mapNotNull { files.write(1_700_000_000_000L, it, byteArrayOf(1, 2, 3, it.toByte())) }
        assertEquals(10, saved.size)
        assertEquals(10, files.count())
        assertEquals(0, files.remaining())
        assertNull(files.write(1_700_000_000_001L, 0, byteArrayOf(9)))
        assertTrue(files.list().all { it.name.endsWith(".jpg") && it.length() == 4L })
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })

        val first = files.list().first().name
        assertTrue(files.delete(first))
        assertEquals(9, files.count())
        assertNotNull(files.write(1_700_000_000_002L, 0, byteArrayOf(7)))
        // Nomi non validi o fuori dalla cartella: rifiutati.
        assertFalse(files.delete("../keystore.properties"))
        assertFalse(files.delete("altro.txt"))
        files.clear()
        assertEquals(0, files.count())
    }
}
