package com.zerobet.safeguard

import com.zerobet.safeguard.guard.Box
import com.zerobet.safeguard.guard.ScrollTracking
import com.zerobet.safeguard.mask.MaskImageFiles
import com.zerobet.safeguard.notify.RemoteCommand
import com.zerobet.safeguard.notify.TelegramApi
import com.zerobet.safeguard.notify.TelegramCommands
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhotoBotAndTrackingTest {

    private val tutor = 424242L

    private fun body(vararg messages: String) =
        """{"ok":true,"result":[${messages.mapIndexed { i, m -> """{"update_id":${100 + i},"message":$m}""" }.joinToString(",")}]}"""

    private fun msg(chat: Long, extra: String) = """{"message_id":1,"chat":{"id":$chat,"type":"private"},"date":1,$extra}"""

    // ---------------------------------------------------------------------------------------
    // Foto, documenti e didascalie dal bot
    // ---------------------------------------------------------------------------------------

    @Test
    fun `foto con didascalia sceglie la dimensione piu grande entro 8 MB`() {
        val photo = msg(tutor, """"photo":[
            {"file_id":"small","width":90,"height":120,"file_size":2000},
            {"file_id":"medium","width":600,"height":800,"file_size":90000},
            {"file_id":"huge","width":4000,"height":5000,"file_size":9500000}],
            "caption":"Pensa a chi ti vuole bene"""")
        val poll = TelegramCommands.parseUpdates(body(photo), tutor)!!
        assertEquals(RemoteCommand.Photo("medium", "Pensa a chi ti vuole bene"), poll.commands.single().command)
        assertEquals(101L, poll.nextOffset)
    }

    @Test
    fun `immagine inviata come file e file non validi`() {
        val doc = msg(tutor, """"document":{"file_id":"doc1","mime_type":"image/jpeg","file_size":300000},"caption":"Famiglia"""")
        val pdf = msg(tutor, """"document":{"file_id":"doc2","mime_type":"application/pdf","file_size":3000}""")
        val big = msg(tutor, """"document":{"file_id":"doc3","mime_type":"image/png","file_size":9000000}""")
        val cmds = TelegramCommands.parseUpdates(body(doc, pdf, big), tutor)!!.commands.map { it.command }
        assertEquals(RemoteCommand.Photo("doc1", "Famiglia"), cmds[0])
        assertTrue(cmds[1] is RemoteCommand.PhotoRejected)
        assertTrue(cmds[2] is RemoteCommand.PhotoRejected)
        // Senza didascalia la foto è valida, la frase resta quella attuale.
        val noCaption = TelegramCommands.photoCommand(JSONObject(msg(tutor, """"photo":[{"file_id":"x","width":10,"height":10}]""")))
        assertEquals(RemoteCommand.Photo("x", ""), noCaption)
    }

    @Test
    fun `foto da una chat estranea ignorata`() {
        val stranger = msg(999999, """"photo":[{"file_id":"p","width":100,"height":100,"file_size":1000}],"caption":"ciao"""")
        val poll = TelegramCommands.parseUpdates(body(stranger), tutor)!!
        assertTrue(poll.commands.isEmpty())
        assertEquals(listOf(999999L), poll.foreignChats)
    }

    @Test
    fun `comandi frase, foto e cancella_foto`() {
        assertEquals(RemoteCommand.SetCaption("Pensa a chi ti vuole bene"), TelegramCommands.parse("/frase Pensa a chi ti vuole bene"))
        assertEquals(RemoteCommand.SetCaption(""), TelegramCommands.parse("/frase off"))
        assertEquals(RemoteCommand.SetCaption(""), TelegramCommands.parse("/frase vuota"))
        assertEquals(RemoteCommand.MissingArgument("/frase"), TelegramCommands.parse("/frase"))
        assertEquals(RemoteCommand.PhotoStatus, TelegramCommands.parse("/foto"))
        assertEquals(RemoteCommand.ClearPhotos, TelegramCommands.parse("/cancella_foto"))
        assertEquals(
            "🖼️ Foto aggiunta ai pannelli oscuranti (3/10 attive) · Frase: \"Pensa a chi ti vuole bene\"",
            TelegramCommands.photoAddedReply(3, 10, "Pensa a chi ti vuole bene")
        )
        assertTrue(TelegramCommands.photoStatusReply(true, 0, 10, "").contains("nessuna foto"))
        assertTrue(TelegramCommands.HELP_TEXT.contains("/cancella_foto") && TelegramCommands.HELP_TEXT.contains("CutBet"))
    }

    @Test
    fun `percorso del file di Telegram`() {
        assertEquals("photos/file_1.jpg", TelegramApi.parseFilePath("""{"ok":true,"result":{"file_id":"a","file_size":1000,"file_path":"photos/file_1.jpg"}}""", 8_000_000))
        assertNull(TelegramApi.parseFilePath("""{"ok":true,"result":{"file_id":"a","file_size":9000000,"file_path":"photos/x.jpg"}}""", 8_000_000))
        assertNull(TelegramApi.parseFilePath("""{"ok":true,"result":{"file_id":"a","file_path":"../../etc/passwd"}}""", 8_000_000))
        assertNull(TelegramApi.parseFilePath("""{"ok":false}""", 8_000_000))
    }

    // ---------------------------------------------------------------------------------------
    // Rotazione FIFO (massimo 10 foto)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `FIFO toglie la foto piu vecchia quando lo store e pieno`() {
        val files = MaskImageFiles(File(Files.createTempDirectory("fifo").toFile(), "mask_images"))
        (0 until 10).forEach { assertNotNull(files.write(1_000L + it, 0, byteArrayOf(it.toByte()))) }
        val oldest = files.list().first().name
        assertEquals(listOf(oldest), files.makeRoom())
        assertNotNull(files.write(2_000L, 0, byteArrayOf(99)))
        assertEquals(10, files.count())
        assertTrue(files.list().none { it.name == oldest })
        // Con spazio libero non si toglie nulla.
        files.delete(files.list().first().name)
        assertTrue(files.makeRoom().isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Pannelli che seguono lo scorrimento
    // ---------------------------------------------------------------------------------------

    private val viewport = Box(0, 210, 1080, 2340)   // area della pagina sotto la barra di Chrome

    @Test
    fun `il pannello segue il nodo e resta nell'area visibile`() {
        val mask = Box(20, 1500, 1060, 2000)
        val anchor = Box(30, 1510, 1050, 1990)
        // Scorrimento di 600 px verso l'alto.
        assertEquals(Box(20, 900, 1060, 1400), ScrollTracking.relocate(mask, anchor, Box(30, 910, 1050, 1390), viewport))
        // In parte sotto la barra degli indirizzi: tagliato all'area della pagina, non la copre.
        assertEquals(Box(20, 210, 1060, 500), ScrollTracking.relocate(mask, anchor, Box(30, 10, 1050, 490), viewport))
        // Uscito dall'area visibile o nodo sparito: pannello nascosto.
        assertNull(ScrollTracking.relocate(mask, anchor, Box(30, -800, 1050, -310), viewport))
        assertNull(ScrollTracking.relocate(mask, anchor, Box(0, 0, 0, 0), viewport))
    }

    @Test
    fun `banner entrato a meta viene coperto tutto mentre entra nello schermo`() {
        // Annuncio alto 500 px, all'inizio visibile solo per 140 px in fondo: pannello tagliato.
        val anchor = Box(30, 2200, 1050, 2700)
        val mask = Box(20, 2190, 1060, 2340)
        // Scorrimento di 900 px: il banner ora è tutto a schermo e il pannello lo copre per intero.
        assertEquals(Box(20, 1290, 1060, 1810), ScrollTracking.relocate(mask, anchor, Box(30, 1300, 1050, 1800), viewport))
        // Annuncio coperto in parte da un pannello più largo (margine): il margine resta.
        assertEquals(Box(0, 990, 1080, 1510), ScrollTracking.relocate(Box(0, 1190, 1080, 1710), Box(30, 1200, 1050, 1700), Box(30, 1000, 1050, 1500), viewport))
    }

    @Test
    fun `riposizionamento velocissimo durante uno scorrimento simulato`() {
        val mask = Box(20, 1500, 1060, 2000)
        val anchor = Box(30, 1510, 1050, 1990)
        // 3 pannelli x 60 eventi al secondo x 10 minuti di scorrimento continuo.
        val iterations = 3 * 60 * 600
        var visible = 0
        repeat(2_000) { ScrollTracking.relocate(mask, anchor, Box(30, 1510 - it % 2000, 1050, 1990 - it % 2000), viewport) }
        val start = System.nanoTime()
        for (i in 0 until iterations) {
            val y = 1510 - (i * 7) % 2600
            if (ScrollTracking.relocate(mask, anchor, Box(30, y, 1050, y + 480), viewport) != null) visible++
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue("visibili: $visible", visible in 1 until iterations)
        // Media ben sotto il microsecondo per calcolo: nessun peso sul thread principale.
        assertTrue("$iterations calcoli in $elapsedMs ms", elapsedMs < 250.0)
    }
}
