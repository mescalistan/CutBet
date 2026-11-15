package com.zerobet.safeguard

import com.zerobet.safeguard.guard.BootWindow
import com.zerobet.safeguard.guard.PollPacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupAndPacingTest {

    private val min = 60_000L

    @Test
    fun `filtro VPN avviato in silenzio solo nella finestra di avvio`() {
        assertTrue(BootWindow.isBootPhase(elapsedNow = 20_000, bootBroadcastAt = null))   // 20 s dall'accensione
        assertTrue(BootWindow.isBootPhase(44_999, null))
        assertFalse(BootWindow.isBootPhase(45_000, null))
        // Telefono lento: BOOT_COMPLETED arriva dopo 60 s, la finestra dura altri 20 s.
        assertTrue(BootWindow.isBootPhase(79_999, bootBroadcastAt = 60_000))
        assertFalse(BootWindow.isBootPhase(80_000, bootBroadcastAt = 60_000))
        // Ore dopo l'avvio, filtro spento: è una manomissione, va segnalata.
        assertFalse(BootWindow.isBootPhase(3 * 60 * min, bootBroadcastAt = 30_000))
        assertFalse(BootWindow.isBootPhase(3 * 60 * min, bootBroadcastAt = null))
    }

    @Test
    fun `polling reattivo a schermo acceso o spento da poco`() {
        val now = 100 * min
        assertEquals(PollPacing.REACTIVE, PollPacing.plan(true, screenOffAt = now - 60 * min, lastCriticalAt = null, now = now))
        assertEquals(PollPacing.REACTIVE, PollPacing.plan(false, screenOffAt = null, lastCriticalAt = null, now = now))
        assertEquals(PollPacing.REACTIVE, PollPacing.plan(false, screenOffAt = now - 4 * min, lastCriticalAt = null, now = now))
        assertEquals(50, PollPacing.REACTIVE.timeoutSec)
        assertTrue(PollPacing.REACTIVE.reactive)
    }

    @Test
    fun `pausa di 90 secondi a schermo spento da oltre 5 minuti`() {
        val now = 100 * min
        val plan = PollPacing.plan(false, screenOffAt = now - 6 * min, lastCriticalAt = null, now = now)
        assertEquals(PollPacing.IDLE, plan)
        assertEquals(90_000L, plan.pauseMs)
        assertEquals(0, plan.timeoutSec)       // richiesta breve: nessuna connessione tenuta aperta
        assertFalse(plan.reactive)
    }

    @Test
    fun `dopo un evento critico 5 minuti di risposta immediata anche a schermo spento`() {
        val now = 100 * min
        assertEquals(PollPacing.REACTIVE, PollPacing.plan(false, now - 30 * min, lastCriticalAt = now - 2 * min, now = now))
        assertEquals(PollPacing.IDLE, PollPacing.plan(false, now - 30 * min, lastCriticalAt = now - 6 * min, now = now))
    }
}
