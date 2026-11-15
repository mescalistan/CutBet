package com.zerobet.safeguard

import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.data.Severity
import com.zerobet.safeguard.notify.AlertChannel
import com.zerobet.safeguard.notify.AlertDispatcher
import com.zerobet.safeguard.notify.AlertPolicy
import com.zerobet.safeguard.notify.AlertPrefs
import com.zerobet.safeguard.notify.AlertQueue
import com.zerobet.safeguard.notify.BlockAggregator
import com.zerobet.safeguard.notify.BlockItem
import com.zerobet.safeguard.notify.FlushOutcome
import com.zerobet.safeguard.notify.QueuedAlert
import com.zerobet.safeguard.notify.SendResult
import com.zerobet.safeguard.notify.TelegramApi
import com.zerobet.safeguard.notify.TelegramTransport
import com.zerobet.safeguard.notify.UpdatesResult
import com.zerobet.safeguard.vpn.DomainFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId

class TelegramNotifierTest {

    private val zone = ZoneId.of("Europe/Rome")
    private val token = "1234567890:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"

    private fun tempQueue(): AlertQueue {
        val dir = Files.createTempDirectory("sgqueue").toFile().apply { deleteOnExit() }
        return AlertQueue(File(dir, "queue.txt"))
    }

    /** Trasporto finto: registra i messaggi e restituisce gli esiti programmati. */
    private class FakeTransport(var online: Boolean = true) : TelegramTransport {
        val sent = ArrayList<String>()
        val script = ArrayDeque<SendResult>()
        var updates: UpdatesResult = UpdatesResult.Ok("""{"ok":true,"result":[]}""")
        override fun sendMessage(token: String, chatId: Long, text: String): SendResult {
            if (!online) return SendResult.NetworkError("UnknownHostException")
            val r = script.removeFirstOrNull() ?: SendResult.Ok
            if (r is SendResult.Ok) sent += text
            return r
        }
        override fun getUpdates(token: String, offset: Long?, timeoutSec: Int) = updates
    }

    // ---------------------------------------------------------------------------------------
    // Token, Chat ID e risposte dell'API
    // ---------------------------------------------------------------------------------------

    @Test
    fun `formato del token di BotFather`() {
        assertTrue(TelegramApi.isValidToken(token))
        assertTrue(TelegramApi.isValidToken("  $token  "))
        assertFalse(TelegramApi.isValidToken(""))
        assertFalse(TelegramApi.isValidToken("123456789"))
        assertFalse(TelegramApi.isValidToken("abc:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"))
        assertFalse(TelegramApi.isValidToken("123456789:corto"))
        assertFalse(TelegramApi.isValidToken("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PA LDsaw"))
        assertEquals(123456789L, TelegramApi.parseChatId(" 123456789 "))
        assertEquals(-1001234567890L, TelegramApi.parseChatId("-1001234567890"))
        assertNull(TelegramApi.parseChatId("abc"))
        assertNull(TelegramApi.parseChatId("0"))
    }

    @Test
    fun `auto-rilevamento del chat id dall'ultimo start`() {
        val body = """
            {"ok":true,"result":[
              {"update_id":1,"message":{"message_id":1,"from":{"id":111,"first_name":"Vecchio"},
                "chat":{"id":111,"first_name":"Vecchio","type":"private"},"date":1,"text":"/start"}},
              {"update_id":2,"message":{"message_id":2,"from":{"id":555,"first_name":"Anna","username":"anna_tutrice"},
                "chat":{"id":555,"first_name":"Anna","last_name":"Rossi","username":"anna_tutrice","type":"private"},"date":2,"text":"/start"}},
              {"update_id":3,"message":{"message_id":3,"from":{"id":555,"first_name":"Anna"},
                "chat":{"id":555,"first_name":"Anna","type":"private"},"date":3,"text":"ciao"}}
            ]}
        """.trimIndent()
        val chat = TelegramApi.parseStartChat(body)
        assertNotNull(chat)
        assertEquals(555L, chat!!.id)
        assertEquals("Anna Rossi (@anna_tutrice)", chat.displayName)
    }

    @Test
    fun `senza start si usa l'ultima chat privata, senza messaggi nessuna chat`() {
        val onlyText = """{"ok":true,"result":[{"update_id":9,"message":{"message_id":1,
            "chat":{"id":42,"username":"tutore","type":"private"},"date":1,"text":"ciao"}}]}"""
        assertEquals(42L, TelegramApi.parseStartChat(onlyText)!!.id)
        assertEquals("@tutore", TelegramApi.parseStartChat(onlyText)!!.displayName)
        assertNull(TelegramApi.parseStartChat("""{"ok":true,"result":[]}"""))
        assertNull(TelegramApi.parseStartChat("""{"ok":false,"error_code":401,"description":"Unauthorized"}"""))
        assertNull(TelegramApi.parseStartChat("non è json"))
    }

    @Test
    fun `esiti di sendMessage`() {
        assertEquals(SendResult.Ok, TelegramApi.parseSendResponse(200, """{"ok":true,"result":{}}"""))
        val retry = TelegramApi.parseSendResponse(429, """{"ok":false,"error_code":429,"parameters":{"retry_after":17}}""")
        assertEquals(SendResult.RetryAfter(17), retry)
        val unauthorized = TelegramApi.parseSendResponse(401, """{"ok":false,"description":"Unauthorized"}""")
        assertTrue(unauthorized is SendResult.Failed && unauthorized.permanent)
        val chatNotFound = TelegramApi.parseSendResponse(400, """{"ok":false,"description":"Bad Request: chat not found"}""")
        assertTrue(chatNotFound is SendResult.Failed && chatNotFound.permanent && chatNotFound.description.contains("chat not found"))
        val serverError = TelegramApi.parseSendResponse(502, "<html>Bad gateway</html>")
        assertTrue(serverError is SendResult.Failed && !serverError.permanent)
    }

    // ---------------------------------------------------------------------------------------
    // Classificazione e testi
    // ---------------------------------------------------------------------------------------

    @Test
    fun `classificazione degli eventi nei tre livelli`() {
        listOf(EventType.SIM, EventType.POWER, EventType.PIN, EventType.TAMPER, EventType.INTEGRITY).forEach {
            assertEquals(it, AlertChannel.CRITICAL, AlertPolicy.channelOf(it, "x"))
        }
        assertEquals(AlertChannel.BLOCKS, AlertPolicy.channelOf(EventType.SEARCH, "Ricerca bloccata su Chrome"))
        assertEquals(AlertChannel.BLOCKS, AlertPolicy.channelOf(EventType.APP, "App bloccata all'apertura"))
        assertEquals(AlertChannel.BLOCKS, AlertPolicy.channelOf(EventType.CONTENT, "Pagina bloccata per i contenuti"))
        assertEquals(AlertChannel.DOMAINS, AlertPolicy.channelOf(EventType.CONTENT, "Pubblicità bloccata su YouTube"))
        assertEquals(AlertChannel.DOMAINS, AlertPolicy.channelOf(EventType.DOMAIN, "bet365.it"))
        assertNull(AlertPolicy.channelOf("ALTRO", "x"))

        val defaults = AlertPrefs()
        assertTrue(defaults.allows(AlertChannel.CRITICAL))
        assertTrue(defaults.allows(AlertChannel.BLOCKS))
        assertFalse(defaults.allows(AlertChannel.DOMAINS))
    }

    @Test
    fun `testo dell'avviso critico con orario esatto`() {
        val ts = java.time.ZonedDateTime.of(2026, 11, 14, 22, 31, 5, 0, zone).toInstant().toEpochMilli()
        val text = AlertPolicy.formatImmediate(Severity.CRITICAL, "SIM rimossa dal dispositivo", "Slot SIM 1", ts, zone)
        assertTrue(text.startsWith("🚨 CutBet · ALLARME CRITICO"))
        assertTrue(text.contains("SIM rimossa dal dispositivo\nSlot SIM 1"))
        assertTrue(text.contains("14/11/2026 22:31:05"))
        assertTrue(AlertPolicy.formatImmediate(Severity.WARNING, "PIN errato", "", ts, zone).startsWith("⚠️"))
    }

    // ---------------------------------------------------------------------------------------
    // Coda persistente
    // ---------------------------------------------------------------------------------------

    @Test
    fun `la coda sopravvive al riavvio ed è in ordine cronologico`() {
        val dir = Files.createTempDirectory("sgq").toFile()
        val file = File(dir, "q.txt")
        AlertQueue(file).apply {
            add(QueuedAlert(3_000, "terzo"))
            add(QueuedAlert(1_000, "primo\ncon a capo\te tab \\ e barra"))
            add(QueuedAlert(2_000, "secondo"))
        }
        val reopened = AlertQueue(file)   // nuovo processo dopo il riavvio
        assertEquals(listOf("primo\ncon a capo\te tab \\ e barra", "secondo", "terzo"), reopened.all().map { it.text })
        reopened.removeFirst(2)
        assertEquals(listOf("terzo"), AlertQueue(file).all().map { it.text })
        reopened.removeFirst(1)
        assertEquals(0, reopened.size())
        assertFalse(file.exists())
    }

    @Test
    fun `coda piena tiene le voci più recenti`() {
        val queue = AlertQueue(File(Files.createTempDirectory("sgq").toFile(), "q.txt"), maxEntries = 5)
        (1..8).forEach { queue.add(QueuedAlert(it * 1000L, "avviso $it")) }
        assertEquals((4..8).map { "avviso $it" }, queue.all().map { it.text })
    }

    @Test
    fun `gli arretrati vengono impacchettati in ordine e segnati come in ritardo`() {
        val now = 10 * 60_000L
        val entries = (1..5).map { QueuedAlert(it * 1000L, "evento $it") }
        val packed = AlertPolicy.pack(entries, now, zone)
        assertEquals(1, packed.size)
        assertEquals(5, packed[0].count)
        val text = packed[0].text
        assertTrue(text.startsWith("📬 5 avvisi"))
        assertTrue(text.indexOf("evento 1") < text.indexOf("evento 2") && text.indexOf("evento 4") < text.indexOf("evento 5"))
        assertTrue(text.contains("Inviato in ritardo"))

        val fresh = AlertPolicy.pack(listOf(QueuedAlert(now - 1000, "subito")), now, zone)
        assertEquals("subito", fresh.single().text)

        val big = (1..40).map { QueuedAlert(it.toLong(), "x".repeat(300)) }
        val chunks = AlertPolicy.pack(big, 0, zone)
        assertTrue(chunks.size > 1)
        assertEquals(40, chunks.sumOf { it.count })
        assertTrue(chunks.all { it.text.length <= AlertPolicy.MAX_MESSAGE_CHARS })
    }

    // ---------------------------------------------------------------------------------------
    // Invio: offline, ritorno della rete, 429, errori di configurazione
    // ---------------------------------------------------------------------------------------

    @Test
    fun `offline gli avvisi restano in coda e partono in ordine al ritorno della rete`() {
        val queue = tempQueue()
        val transport = FakeTransport(online = false)
        val dispatcher = AlertDispatcher(queue, transport, zone, clock = { 0L }, pause = {})
        queue.add(QueuedAlert(1_000, "SIM rimossa"))
        assertTrue(dispatcher.flush(token, 42L) is FlushOutcome.Offline)
        queue.add(QueuedAlert(2_000, "Telefono riavviato"))
        assertTrue(dispatcher.flush(token, 42L) is FlushOutcome.Offline)
        assertEquals(2, queue.size())

        transport.online = true
        assertEquals(FlushOutcome.Done, dispatcher.flush(token, 42L))
        assertEquals(0, queue.size())
        val message = transport.sent.single()
        assertTrue(message.indexOf("SIM rimossa") < message.indexOf("Telefono riavviato"))
    }

    @Test
    fun `con il 429 si ferma e conserva il resto della coda`() {
        val queue = tempQueue()
        val transport = FakeTransport()
        transport.script += SendResult.Ok
        transport.script += SendResult.RetryAfter(30)
        val dispatcher = AlertDispatcher(queue, transport, zone, clock = { 0L }, pause = {})
        (1..40).forEach { queue.add(QueuedAlert(it.toLong(), "evento $it " + "x".repeat(250))) }
        val outcome = dispatcher.flush(token, 42L)
        assertEquals(FlushOutcome.RetryAfter(30_000), outcome)
        assertEquals(1, transport.sent.size)
        assertTrue(queue.size() in 1..39)
        assertTrue(queue.all().first().text.startsWith("evento"))
    }

    @Test
    fun `token revocato o chat inesistente non svuotano la coda`() {
        val queue = tempQueue()
        val transport = FakeTransport()
        transport.script += SendResult.Failed(401, "token non valido", permanent = true)
        val dispatcher = AlertDispatcher(queue, transport, zone, clock = { 0L }, pause = {})
        queue.add(QueuedAlert(1, "avviso"))
        assertTrue(dispatcher.flush(token, 42L) is FlushOutcome.ConfigError)
        assertEquals(1, queue.size())
        assertEquals(FlushOutcome.NotConfigured, dispatcher.flush(null, 42L))
        assertEquals(FlushOutcome.NotConfigured, dispatcher.flush(token, null))
    }

    // ---------------------------------------------------------------------------------------
    // Aggregazione dei blocchi (anti 429)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `una raffica di blocchi diventa un solo messaggio al minuto`() {
        val agg = BlockAggregator(windowMs = 60_000, gatherMs = 3_000)
        val first = agg.add(BlockItem(0, "Ricerca bloccata su Chrome: \"trading online\"", ""), now = 0)
        assertEquals(3_000L, first)
        repeat(30) { i -> assertNull(agg.add(BlockItem(100L + i, "Dominio bloccato", "bet365.it"), now = 100L + i)) }
        val message = agg.drain(3_000, zone)!!
        assertTrue(message.text.contains("31 blocchi"))
        assertTrue(message.text.contains("(x30)"))
        assertEquals(1, message.text.lines().count { it.startsWith("• ") && it.contains("Dominio bloccato") })

        // Nuovo blocco 10 s dopo l'invio: aspetta la fine della finestra di 60 s.
        val next = agg.add(BlockItem(13_000, "App bloccata all'apertura: Investing", ""), now = 13_000)
        assertEquals(50_000L, next)
        assertTrue(agg.drain(63_000, zone)!!.text.contains("1 blocco"))
        assertNull(agg.drain(70_000, zone))
    }

    @Test
    fun `il messaggio aggregato elenca al massimo 15 righe`() {
        val agg = BlockAggregator()
        (1..25).forEach { agg.add(BlockItem(it.toLong(), "dominio$it.com", ""), now = it.toLong()) }
        val text = agg.drain(5_000, zone)!!.text
        assertEquals(15, text.lines().count { it.startsWith("• ") })
        assertTrue(text.contains("…e altri 10"))
    }

    // ---------------------------------------------------------------------------------------
    // La VPN non blocca mai l'API di Telegram
    // ---------------------------------------------------------------------------------------

    @Test
    fun `api telegram sempre consentita dal filtro DNS`() {
        val hostile = DomainFilter(
            whitelist = emptySet(),
            blockedDomains = setOf("telegram.org", "api.telegram.org"),
            keywords = listOf("telegram"),
        )
        assertFalse(hostile.shouldBlock("api.telegram.org"))
        assertFalse(hostile.shouldBlock("API.Telegram.org."))
        assertTrue(hostile.shouldBlock("web.telegram.org"))
    }
}
