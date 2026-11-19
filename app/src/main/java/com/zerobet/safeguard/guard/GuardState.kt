package com.zerobet.safeguard.guard

import android.os.SystemClock

/**
 * Stato condiviso (in memoria, stesso processo) tra Guardiano, LockScreenActivity e dashboard.
 *
 * Dopo un PIN corretto il tutore ottiene una "finestra di grazia" durante la quale il Guardiano
 * non interviene, così può modificare le Impostazioni di sistema. Usa l'orologio monotono
 * (elapsedRealtime) così non è aggirabile cambiando data/ora del telefono.
 */
object GuardState {
    const val GRACE_AFTER_PIN_MS = 60_000L
    const val GRACE_FOR_UNINSTALL_MS = 5 * 60_000L

    @Volatile private var graceUntil = 0L

    /** true mentre la LockScreenActivity è visibile: evita di rilanciarla a raffica. */
    @Volatile var lockScreenVisible = false

    /** true se la finestra di grazia corrente serve solo per le Impostazioni di sistema. */
    @Volatile var settingsOnly = false
        private set

    fun grant(durationMs: Long) {
        settingsOnly = false
        graceUntil = SystemClock.elapsedRealtime() + durationMs
    }

    /**
     * Grazia per una schermata delle Impostazioni aperta dalla dashboard (Accessibilità, VPN,
     * amministratore): finisce appena il tutore passa a un'altra app (vedi [leavesSettings]),
     * così la protezione riparte subito invece di restare muta fino allo scadere del minuto.
     */
    fun grantForSettings(durationMs: Long) {
        graceUntil = SystemClock.elapsedRealtime() + durationMs
        settingsOnly = true
    }

    fun revoke() {
        graceUntil = 0L
        settingsOnly = false
    }

    /**
     * Kotlin puro: true se una finestra aperta da [packageName] significa "il tutore ha lasciato le
     * Impostazioni". Home, barra di sistema e tastiera ([ignored]) e CutBet stesso non contano.
     */
    fun leavesSettings(packageName: String, ownPackage: String, settingsPackages: Set<String>, ignored: Set<String>): Boolean =
        packageName != ownPackage && packageName !in settingsPackages && packageName !in ignored

    val inGrace: Boolean
        get() = SystemClock.elapsedRealtime() < graceUntil
}
