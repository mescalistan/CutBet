package com.zerobet.safeguard.security

import android.content.BroadcastReceiver
import android.os.SystemClock
import android.util.Log
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.data.Severity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spegnimento del telefono (ACTION_SHUTDOWN, QUICKBOOT_POWEROFF).
 *
 * Android chiude il processo pochi istanti dopo il broadcast: niente coroutine né scritture
 * asincrone. Tutto avviene in modo sincrono dentro il receiver (tenuto aperto con goAsync):
 *  1. flag e orario dello spegnimento con commit() nello storage Direct Boot;
 *  2. avviso nella coda Telegram su disco, con fsync;
 *  3. in parallelo: invio lampo su Telegram e riga nel Registro Allarmi (Room), entro [BUDGET_MS];
 *  4. pendingResult.finish().
 * Al riavvio successivo [onBoot] completa ciò che non ha fatto in tempo: la riga nel registro
 * (con l'orario originale) e, se l'invio non era riuscito, l'avviso parte dalla coda.
 */
object ShutdownRecorder {

    const val TITLE = "Telefono spento o riavviato"
    private const val DETAIL = "Spegnimento richiesto"
    const val BUDGET_MS = 1_500L

    private const val KEY_AT = "shutdown_pending_at"
    private const val KEY_LOGGED = "shutdown_logged"
    private const val KEY_SENT = "shutdown_sent"
    private const val TAG = "SafeGuardShutdown"

    private val handled = AtomicBoolean(false)

    fun onShutdown(app: SafeGuardApp, pending: BroadcastReceiver.PendingResult?) {
        try {
            if (!handled.compareAndSet(false, true)) return   // broadcast doppio (manifest + runtime)
            val start = SystemClock.elapsedRealtime()
            val at = TrustedClock.now()
            val prefs = DeviceStorage.statePrefs(app)
            prefs.edit().putLong(KEY_AT, at).putBoolean(KEY_LOGGED, false).putBoolean(KEY_SENT, false).commit()
            PowerHistory.markShutdown(app)
            if (!app.pinManager.onboardingDone) return

            val queued = app.telegram.enqueueImmediate(Severity.WARNING, EventType.POWER, TITLE, DETAIL, at)
            val sender = if (queued) {
                Thread {
                    if (app.telegram.flushBlocking()) prefs.edit().putBoolean(KEY_SENT, true).commit()
                }.apply { isDaemon = true; start() }
            } else null

            val logged = app.securityLog.logNow(
                EventType.POWER, Severity.WARNING, TITLE, DETAIL, timeoutMs = BUDGET_MS, notify = false, timestamp = at
            )
            if (logged) prefs.edit().putBoolean(KEY_LOGGED, true).commit()
            sender?.join((BUDGET_MS - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(1))
            Log.i(TAG, "Spegnimento registrato: registro=$logged, inviato=${prefs.getBoolean(KEY_SENT, false)}")
        } catch (e: Exception) {
            Log.w(TAG, "Registrazione dello spegnimento incompleta: ${e.javaClass.simpleName}")
        } finally {
            pending?.finish()
        }
    }

    /** Al primo avvio dopo uno spegnimento: completa la registrazione se non era riuscita. */
    fun onBoot(app: SafeGuardApp) {
        val prefs = DeviceStorage.statePrefs(app)
        val at = prefs.getLong(KEY_AT, 0L)
        if (at == 0L) return
        val logged = prefs.getBoolean(KEY_LOGGED, false)
        val sent = prefs.getBoolean(KEY_SENT, false)
        prefs.edit().remove(KEY_AT).remove(KEY_LOGGED).remove(KEY_SENT).commit()
        if (logged || !app.pinManager.onboardingDone) return
        val downtime = PowerHistory.lastBootDowntime(app)
        app.securityLog.logAt(
            at, EventType.POWER, Severity.WARNING, TITLE,
            buildString {
                append(DETAIL).append(" · registrato al riavvio")
                if (downtime != null) append(" · spento per ").append(ClockGuard.formatDuration(downtime))
                if (!sent) append(" · avviso Telegram inviato al riavvio")
            },
            notify = false
        )
    }
}
