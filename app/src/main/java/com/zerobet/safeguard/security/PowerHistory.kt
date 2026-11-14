package com.zerobet.safeguard.security

import android.content.Context

/**
 * Ultimo istante in cui SafeGuard era sicuramente in funzione e ultimo spegnimento regolare.
 *
 * In Modalità Provvisoria (o a telefono spento) nessuna app di terze parti gira: al primo avvio
 * normale successivo il "buco" tra l'ultimo segnale di vita e l'avvio dice al tutore per quanto
 * tempo la protezione è mancata. Il calcolo avviene all'avvio del processo, prima che qualsiasi
 * altra parte dell'app aggiorni il segnale di vita.
 */
object PowerHistory {

    private const val KEY_ALIVE = "power_last_alive"
    private const val KEY_SHUTDOWN = "power_last_shutdown"
    private const val KEY_BOOT = "power_boot"
    private const val KEY_DOWNTIME = "power_boot_downtime"

    fun onProcessStart(context: Context) {
        val prefs = DeviceStorage.statePrefs(context)
        val boot = TrustedClock.bootCount(context)
        if (prefs.getInt(KEY_BOOT, -2) != boot) {
            val last = maxOf(prefs.getLong(KEY_ALIVE, 0L), prefs.getLong(KEY_SHUTDOWN, 0L))
            val downtime = if (last > 0) (TrustedClock.now() - last).coerceAtLeast(0) else -1L
            prefs.edit().putInt(KEY_BOOT, boot).putLong(KEY_DOWNTIME, downtime).apply()
        }
        markAlive(context)
    }

    fun markAlive(context: Context) {
        DeviceStorage.statePrefs(context).edit().putLong(KEY_ALIVE, TrustedClock.now()).apply()
    }

    /** Scrittura sincrona: chiamata durante lo spegnimento. */
    fun markShutdown(context: Context) {
        DeviceStorage.statePrefs(context).edit().putLong(KEY_SHUTDOWN, TrustedClock.now()).commit()
    }

    /** Durata del periodo senza protezione prima dell'avvio corrente (null se sconosciuta). */
    fun lastBootDowntime(context: Context): Long? =
        DeviceStorage.statePrefs(context).getLong(KEY_DOWNTIME, -1L).takeIf { it >= 0 }
}
