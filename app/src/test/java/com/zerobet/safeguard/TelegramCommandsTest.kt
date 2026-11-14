package com.zerobet.safeguard

import com.zerobet.safeguard.data.SecurityEventLog
import com.zerobet.safeguard.data.Severity
import com.zerobet.safeguard.notify.RemoteCommand
import com.zerobet.safeguard.notify.TelegramCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TelegramCommandsTest {

    private val zone = ZoneId.of("Europe/Rome")
    private val tutor = 424242L

    private fun update(id: Long, chat: Long, text: String, edited: Boolean = false) =
        """{"update_id":$id,"${if (edited) "edited_message" else "message"}":{"message_id":$id,
           "from":{"id":$chat,"first_name":"X"},"chat":{"id":$chat,"type":"private"},"date":1,"text":${org.json.JSONObject.quote(text)}}}"""

    private fun body(vararg updates: String) = """{"ok":true,"result":[${updates.joinToString(",")}]}"""

    @Test
    fun `riconoscimento dei comandi`() {
        assertEquals(RemoteCommand.Status, TelegramCommands.parse("/stato"))
        assertEquals(RemoteCommand.Status, TelegramCommands.parse("/report"))
        assertEquals(RemoteCommand.Status, TelegramCommands.parse("  /STATO  "))
        assertEquals(RemoteCommand.Status, TelegramCommands.parse("/stato@MioSafeGuard_bot"))
        assertEquals(RemoteCommand.Latest, TelegramCommands.parse("/ultimi"))
        assertEquals(RemoteCommand.Help, TelegramCommands.parse("/aiuto"))
        assertEquals(RemoteCommand.Help, TelegramCommands.parse("/start"))
        assertEquals(RemoteCommand.Block("nuovosito.com"), TelegramCommands.parse("/blocca nuovosito.com"))
        assertEquals(RemoteCommand.Block("gratta e vinci"), TelegramCommands.parse("/blocca@MioSafeGuard_bot   gratta e vinci "))
        assertEquals(RemoteCommand.Allow("miabanca.it"), TelegramCommands.parse("/consenti miabanca.it"))
        assertEquals(RemoteCommand.MissingArgument("/blocca"), TelegramCommands.parse("/blocca"))
        assertEquals(RemoteCommand.MissingArgument("/consenti"), TelegramCommands.parse("/consenti   "))
        assertEquals(RemoteCommand.Unknown("/disattiva"), TelegramCommands.parse("/disattiva tutto"))
        assertNull(TelegramCommands.parse("ciao, tutto bene?"))
        assertNull(TelegramCommands.parse("/"))
        assertNull(TelegramCommands.parse(""))
        assertTrue(TelegramCommands.isDomainTarget("nuovosito.com"))
        assertFalse(TelegramCommands.isDomainTarget("scommesse"))
    }

    @Test
    fun `solo la chat del tutore puo comandare`() {
        val poll = TelegramCommands.parseUpdates(
            body(
                update(10, tutor, "/stato"),
                update(11, 999999, "/consenti bet365.com"),     // estraneo: ignorato
                update(12, -100123, "/blocca google.com"),      // gruppo: ignorato
                update(13, tutor, "/blocca nuovosito.com"),
            ),
            tutor
        )!!
        assertEquals(listOf(RemoteCommand.Status, RemoteCommand.Block("nuovosito.com")), poll.commands.map { it.command })
        assertEquals(listOf(999999L, -100123L), poll.foreignChats)
        assertEquals(14L, poll.nextOffset)
    }

    @Test
    fun `messaggi modificati e testo libero non eseguono nulla, ma l'offset avanza`() {
        val poll = TelegramCommands.parseUpdates(
            body(update(20, tutor, "/consenti bet365.com", edited = true), update(21, tutor, "grazie!")),
            tutor
        )!!
        assertTrue(poll.commands.isEmpty())
        assertTrue(poll.foreignChats.isEmpty())
        assertEquals(22L, poll.nextOffset)
    }

    @Test
    fun `risposte non valide e coda vuota`() {
        assertNull(TelegramCommands.parseUpdates("""{"ok":false,"error_code":409}""", tutor))
        assertNull(TelegramCommands.parseUpdates("non json", tutor))
        val empty = TelegramCommands.parseUpdates("""{"ok":true,"result":[]}""", tutor)!!
        assertTrue(empty.commands.isEmpty())
        assertNull(empty.nextOffset)
        assertEquals(8L, TelegramCommands.nextOffset(body(update(5, tutor, "/start"), update(7, tutor, "x"))))
        assertNull(TelegramCommands.nextOffset("""{"ok":true,"result":[]}"""))
    }

    @Test
    fun `report di stato`() {
        val now = ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val text = TelegramCommands.formatStatus(
            TelegramCommands.DeviceStatus(
                vpnOk = true, protectionPaused = false, guardOk = true, uptimeMs = 26 * 3_600_000L,
                batteryPercent = 76, charging = true, domains = 40, searches = 2, settingsAttempts = 1,
                lastListsUpdate = now - 3_600_000L, pendingAlerts = 0, clockOffsetMs = 0
            ),
            now, zone
        )
        assertTrue(text.contains("Filtro VPN: attivo"))
        assertTrue(text.contains("Guardiano Impostazioni: attivo"))
        assertTrue(text.contains("Acceso da: 1 giorno e 2 ore (ultimo riavvio 14/11/2026 08:00)"))
        assertTrue(text.contains("Batteria: 76% (in carica)"))
        assertTrue(text.contains("40 domini, 2 ricerche, 1 tentativo impostazioni"))
        assertFalse(text.contains("manomesso"))

        val bad = TelegramCommands.formatStatus(
            TelegramCommands.DeviceStatus(false, false, false, 60_000, null, false, 0, 0, 0, null, 3, 4 * 365 * 86_400_000L),
            now, zone
        )
        assertTrue(bad.contains("Filtro VPN: SPENTO") && bad.contains("Guardiano Impostazioni: SPENTO"))
        assertTrue(bad.contains("Batteria: non disponibile") && bad.contains("Avvisi in coda: 3"))
        assertTrue(bad.contains("Orologio del telefono manomesso (in avanti di 4 anni"))
    }

    @Test
    fun `ultimi eventi`() {
        val t = ZonedDateTime.of(2026, 11, 15, 9, 30, 0, 0, zone).toInstant().toEpochMilli()
        val text = TelegramCommands.formatLatest(
            listOf(
                SecurityEventLog(1, t, Severity.CRITICAL, "SIM", "SIM rimossa dal dispositivo", "Slot SIM 1"),
                SecurityEventLog(2, t - 60_000, Severity.WARNING, "SEARCH", "Ricerca bloccata", "", count = 3),
            ),
            zone
        )
        assertTrue(text.startsWith("🗂 Ultimi 2 eventi"))
        assertTrue(text.contains("🚨 15/11/2026 09:30 SIM rimossa dal dispositivo\n   Slot SIM 1"))
        assertTrue(text.contains("⚠️ 15/11/2026 09:29 Ricerca bloccata (x3)"))
        assertEquals("🗂 Il Registro Allarmi è vuoto.", TelegramCommands.formatLatest(emptyList(), zone))
    }
}
