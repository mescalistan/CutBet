package com.zerobet.safeguard

import com.zerobet.safeguard.notify.AlertPolicy
import com.zerobet.safeguard.notify.AlertPrefs
import com.zerobet.safeguard.security.ClockGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RedTeamHardeningTest {

    private val zone = ZoneId.of("Europe/Rome")
    private val hour = 60 * 60_000L
    private val day = 24 * hour

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    // ---------------------------------------------------------------------------------------
    // Manomissione di data/ora
    // ---------------------------------------------------------------------------------------

    @Test
    fun `orologio portato nel 2030 o nel 2020 rilevato dal tempo monotono`() {
        val now = at(2026, 11, 14)
        val base = ClockGuard.Baseline(trueWall = now, elapsed = 1_000_000, bootCount = 7)
        // 10 minuti dopo (tempo monotòno), l'orologio segna il 2030.
        val forward = ClockGuard.offset(base, at(2030, 1, 1), 1_000_000 + 10 * 60_000, 7)!!
        assertTrue(ClockGuard.isTamper(forward))
        assertTrue(forward > 0)
        val back = ClockGuard.offset(base, at(2020, 1, 1), 1_000_000 + 10 * 60_000, 7)!!
        assertTrue(ClockGuard.isTamper(back))
        assertTrue(back < 0)
        assertTrue(ClockGuard.describeOffset(forward).startsWith("in avanti di 3 anni"))
        assertTrue(ClockGuard.describeOffset(back).startsWith("indietro di 6 anni"))
    }

    @Test
    fun `piccole correzioni automatiche non sono manomissioni`() {
        val base = ClockGuard.Baseline(trueWall = 1_000_000_000_000, elapsed = 5_000, bootCount = 3)
        val drift = ClockGuard.offset(base, 1_000_000_000_000 + 60_000 + 40_000, 5_000 + 60_000, 3)!!
        assertEquals(40_000L, drift)
        assertFalse(ClockGuard.isTamper(drift))
        // Riferimento di un avvio precedente: il tempo monotòno non è confrontabile.
        assertNull(ClockGuard.offset(base, 0, 10, 4))
        assertNull(ClockGuard.offset(null, 0, 10, 3))
    }

    @Test
    fun `orologio portato indietro a telefono spento`() {
        val lastTrue = at(2026, 11, 14, 20)
        assertTrue(ClockGuard.wentBackAcrossReboot(lastTrue, at(2020, 1, 1)))
        assertFalse(ClockGuard.wentBackAcrossReboot(lastTrue, lastTrue + hour))   // spento per un'ora: normale
        assertFalse(ClockGuard.wentBackAcrossReboot(lastTrue, lastTrue - 60_000)) // 1 minuto: correzione
    }

    @Test
    fun `il blocco del PIN non si accorcia spostando l'orologio`() {
        val duration = 15 * 60_000L
        // Stesso avvio: conta solo il tempo monotòno, anche se l'orologio è nel 2030.
        val sameBoot = ClockGuard.lockoutRemaining(
            sameBoot = true, elapsedUntil = 2_000_000 + duration, elapsedNow = 2_000_000 + 60_000,
            wallUntil = at(2026, 11, 14) + duration, wallNow = at(2030, 1, 1), durationMs = duration,
        )
        assertEquals(duration - 60_000, sameBoot)
        // Dopo un riavvio con l'orologio portato indietro il blocco non supera la sua durata.
        val back = ClockGuard.lockoutRemaining(
            sameBoot = false, elapsedUntil = 0, elapsedNow = 0,
            wallUntil = at(2026, 11, 14) + duration, wallNow = at(2020, 1, 1), durationMs = duration,
        )
        assertEquals(duration, back)
        assertEquals(0L, ClockGuard.lockoutRemaining(true, 0, 10, 0, 0, 0))
    }

    @Test
    fun `durate in italiano`() {
        assertEquals("meno di un minuto", ClockGuard.formatDuration(30_000))
        assertEquals("1 minuto", ClockGuard.formatDuration(60_000))
        assertEquals("45 minuti", ClockGuard.formatDuration(45 * 60_000))
        assertEquals("3 ore e 20 minuti", ClockGuard.formatDuration(3 * hour + 20 * 60_000))
        assertEquals("1 ora", ClockGuard.formatDuration(hour))
        assertEquals("2 giorni e 4 ore", ClockGuard.formatDuration(2 * day + 4 * hour))
        assertEquals("1 mese e 5 giorni", ClockGuard.formatDuration(35 * day))
        assertEquals("4 anni e 2 mesi", ClockGuard.formatDuration(4 * 365 * day + 61 * day))
    }

    // ---------------------------------------------------------------------------------------
    // Riavvio dopo uno spegnimento prolungato (o Modalità Provvisoria)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `titolo del riavvio con la durata dello spegnimento`() {
        assertEquals("Telefono riavviato", ClockGuard.rebootTitle(null))
        assertEquals("Telefono riavviato", ClockGuard.rebootTitle(70_000))
        assertEquals(
            "Telefono riavviato (era spento o in Modalità Provvisoria da 3 ore e 20 minuti)",
            ClockGuard.rebootTitle(3 * hour + 20 * 60_000)
        )
    }

    // ---------------------------------------------------------------------------------------
    // Report giornaliero (heartbeat)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `report giornaliero con protezione completa`() {
        val now = at(2026, 11, 14, 9)
        val text = AlertPolicy.formatHeartbeat(
            AlertPolicy.HeartbeatStats(true, true, domains = 120, searches = 3, settingsAttempts = 1, lastListsUpdate = now - hour),
            now, zone
        )
        assertTrue(text.startsWith("💓 CutBet Attivo · Protezione VPN e Guardiano: OK"))
        assertTrue(text.contains("Blocchi ultime 24h: 120 domini, 3 ricerche, 1 tentativo impostazioni"))
        assertTrue(text.contains("Liste aggiornate."))
        assertTrue(text.contains("14/11/2026 09:00:00"))
    }

    @Test
    fun `report giornaliero segnala servizi spenti e liste vecchie`() {
        val now = at(2026, 11, 14, 9)
        val text = AlertPolicy.formatHeartbeat(
            AlertPolicy.HeartbeatStats(false, true, 0, 0, 5, lastListsUpdate = now - 5 * day), now, zone
        )
        assertTrue(text.startsWith("⚠️ CutBet attivo ma INCOMPLETO"))
        assertTrue(text.contains("VPN SPENTA"))
        assertTrue(text.contains("5 tentativi impostazioni"))
        assertTrue(text.contains("Liste NON aggiornate dal 09/11/2026"))
        val never = AlertPolicy.formatHeartbeat(AlertPolicy.HeartbeatStats(true, false, 0, 0, 0, null), now, zone)
        assertTrue(never.contains("Guardiano SPENTO") && never.contains("mai aggiornate"))
    }

    @Test
    fun `heartbeat attivo di default`() {
        assertTrue(AlertPrefs().heartbeat)
    }
}
