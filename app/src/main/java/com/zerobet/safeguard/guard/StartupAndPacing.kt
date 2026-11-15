package com.zerobet.safeguard.guard

/**
 * Finestra di avvio del telefono. Subito dopo l'accensione il filtro VPN non è ancora partito
 * (lo avvia il BootReceiver, ma il servizio impiega qualche secondo): un controllo di integrità
 * in quel momento lo troverebbe "spento". Non è una manomissione, quindi lo si avvia in silenzio,
 * senza allarme né avviso Telegram. Kotlin puro, coperto da unit test.
 */
object BootWindow {
    /** Primi secondi dall'accensione (tempo monotòno). */
    const val SINCE_POWER_ON_MS = 45_000L
    /** Secondi dopo LOCKED_BOOT_COMPLETED / BOOT_COMPLETED (arrivano anche più tardi su telefoni lenti). */
    const val SINCE_BOOT_BROADCAST_MS = 15_000L

    fun isBootPhase(elapsedNow: Long, bootBroadcastAt: Long?): Boolean =
        elapsedNow < SINCE_POWER_ON_MS ||
            (bootBroadcastAt != null && elapsedNow >= bootBroadcastAt && elapsedNow - bootBroadcastAt < SINCE_BOOT_BROADCAST_MS)
}

/**
 * Ritmo del polling dei comandi Telegram. Kotlin puro, coperto da unit test.
 *
 *  - Schermo acceso, spento da meno di 5 minuti o evento critico negli ultimi 5 minuti:
 *    long polling reattivo (risposta in meno di un secondo).
 *  - Schermo spento da oltre 5 minuti: una richiesta breve ogni 90 secondi, nessuna connessione
 *    tenuta aperta. La pausa si interrompe all'accensione dello schermo o a un nuovo evento.
 * Tutti i tempi sono monotòni (elapsedRealtime).
 */
object PollPacing {
    const val LONG_POLL_S = 50
    const val SCREEN_OFF_GRACE_MS = 5 * 60_000L
    const val CRITICAL_GRACE_MS = 5 * 60_000L
    const val IDLE_PAUSE_MS = 90_000L

    /** [pauseMs] prima della richiesta, [timeoutSec] del long polling (0 = richiesta breve). */
    data class Plan(val pauseMs: Long, val timeoutSec: Int) {
        val reactive: Boolean get() = pauseMs == 0L
    }

    val REACTIVE = Plan(0, LONG_POLL_S)
    val IDLE = Plan(IDLE_PAUSE_MS, 0)

    fun plan(interactive: Boolean, screenOffAt: Long?, lastCriticalAt: Long?, now: Long): Plan = when {
        interactive -> REACTIVE
        screenOffAt == null || now - screenOffAt < SCREEN_OFF_GRACE_MS -> REACTIVE
        lastCriticalAt != null && now - lastCriticalAt < CRITICAL_GRACE_MS -> REACTIVE
        else -> IDLE
    }
}
