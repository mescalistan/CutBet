package com.zerobet.safeguard.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.notify.AlertPolicy
import java.time.ZoneId

/**
 * Ora "attendibile" di SafeGuard: l'ora del telefono meno lo scarto rilevato quando qualcuno ha
 * spostato data/ora (vedi [ClockGuard]). La usano il Registro Allarmi e gli avvisi Telegram, così
 * un orologio portato nel 2030 non falsa gli orari degli eventi.
 *
 * Stato nello storage protetto dal dispositivo (Direct Boot): riferimento (ora attendibile,
 * tempo monotòno, numero di avvio) e scarto corrente.
 */
object TrustedClock {

    private const val KEY_TRUE_WALL = "clock_true_wall"
    private const val KEY_ELAPSED = "clock_elapsed"
    private const val KEY_BOOT = "clock_boot"
    private const val KEY_OFFSET = "clock_offset"

    @Volatile private var offsetMs = 0L

    /** Scarto corrente dell'orologio di sistema (0 = orologio corretto). */
    val currentOffsetMs: Long get() = offsetMs

    fun now(): Long = System.currentTimeMillis() - offsetMs

    fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) {
        -1
    }

    private fun readBaseline(context: Context): ClockGuard.Baseline? {
        val prefs = DeviceStorage.statePrefs(context)
        if (!prefs.contains(KEY_TRUE_WALL)) return null
        return ClockGuard.Baseline(prefs.getLong(KEY_TRUE_WALL, 0), prefs.getLong(KEY_ELAPSED, 0), prefs.getInt(KEY_BOOT, -2))
    }

    /** Aggiorna il riferimento (chiamato all'avvio, dal controllo periodico e dopo ogni cambio d'ora). */
    fun saveBaseline(context: Context) {
        DeviceStorage.statePrefs(context).edit()
            .putLong(KEY_TRUE_WALL, now())
            .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime())
            .putInt(KEY_BOOT, bootCount(context))
            .putLong(KEY_OFFSET, offsetMs)
            .apply()
    }

    /**
     * All'avvio del processo. Se è un nuovo avvio del telefono e l'orologio segna un istante
     * precedente all'ultima ora attendibile, è stato portato indietro a telefono spento.
     */
    fun init(context: Context) {
        val prefs = DeviceStorage.statePrefs(context)
        val base = readBaseline(context)
        val boot = bootCount(context)
        if (base != null && base.bootCount == boot) {
            // Stesso avvio (il processo è ripartito): lo scarto salvato è ancora valido.
            offsetMs = prefs.getLong(KEY_OFFSET, 0L)
        } else if (base != null) {
            val deviceWall = System.currentTimeMillis()
            offsetMs = if (ClockGuard.wentBackAcrossReboot(base.trueWall, deviceWall)) deviceWall - base.trueWall else 0L
            if (offsetMs != 0L) report(context, offsetMs, "a telefono spento")
        }
        saveBaseline(context)
    }

    /** ACTION_TIME_CHANGED: confronta l'ora nuova con quella attesa dal tempo monotòno. */
    fun onTimeChanged(context: Context) {
        val offset = ClockGuard.offset(readBaseline(context), System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount(context))
        if (offset == null) {
            saveBaseline(context)
            return
        }
        val previous = offsetMs
        if (ClockGuard.isTamper(offset)) {
            offsetMs = offset
            // Nuova manomissione, o un ulteriore spostamento rilevante di un orologio già manomesso.
            if (previous == 0L || ClockGuard.isTamper(offset - previous)) report(context, offset, null)
        } else if (previous != 0L) {
            offsetMs = 0L
            log(context) { it.securityLog.info(EventType.TAMPER, "Data/ora di sistema ripristinata", "L'orologio del telefono è di nuovo corretto") }
        }
        saveBaseline(context)
    }

    fun onTimeZoneChanged(context: Context, zoneId: String?) {
        log(context) {
            it.securityLog.warning(
                EventType.TAMPER, "Fuso orario modificato",
                "Nuovo fuso: ${zoneId ?: ZoneId.systemDefault().id}. Gli orari del registro restano corretti."
            )
        }
    }

    private fun report(context: Context, offset: Long, whenText: String?) {
        val zone = ZoneId.systemDefault()
        val detail = buildString {
            append("Orologio spostato ").append(ClockGuard.describeOffset(offset))
            if (whenText != null) append(" ").append(whenText)
            append(". Il telefono segna ").append(AlertPolicy.dateTime(System.currentTimeMillis(), zone))
            append(", ora reale stimata ").append(AlertPolicy.dateTime(now(), zone)).append('.')
        }
        log(context) { it.securityLog.critical(EventType.TAMPER, "Manomissione data/ora di sistema rilevata", detail) }
    }

    private inline fun log(context: Context, block: (SafeGuardApp) -> Unit) {
        val app = context.applicationContext as? SafeGuardApp ?: return
        if (!app.pinManager.onboardingDone) return
        block(app)
    }
}
