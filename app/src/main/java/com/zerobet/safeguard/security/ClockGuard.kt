package com.zerobet.safeguard.security

import kotlin.math.abs

/**
 * Difesa dalla manomissione di data/ora di sistema. Kotlin puro, coperto da unit test.
 *
 * Spostare l'orologio (nel 2020 o nel 2030) rompe i certificati TLS, falsa gli orari del
 * Registro Allarmi e potrebbe accorciare i blocchi temporanei. Il riferimento affidabile è il
 * tempo monotòno (`SystemClock.elapsedRealtime()`): avanza sempre e non si può impostare.
 * Si salva una coppia (ora "vera", tempo monotòno) e a ogni cambio d'ora si confronta l'ora del
 * telefono con quella attesa: lo scarto è la manomissione.
 */
object ClockGuard {

    /** Sotto questa soglia lo scarto è una normale correzione (rete, NTP, ora legale gestita dal sistema). */
    const val TAMPER_THRESHOLD_MS = 5 * 60_000L

    /** Riferimento salvato: ora attendibile e tempo monotòno nello stesso istante, con il numero di avvio. */
    data class Baseline(val trueWall: Long, val elapsed: Long, val bootCount: Int)

    /**
     * Scarto tra l'ora del telefono e quella attesa dal tempo monotòno (positivo = orologio avanti).
     * null se il riferimento è di un avvio precedente (il tempo monotòno riparte da zero).
     */
    fun offset(base: Baseline?, deviceWall: Long, elapsedNow: Long, bootCount: Int): Long? {
        if (base == null || base.bootCount != bootCount || elapsedNow < base.elapsed) return null
        val expected = base.trueWall + (elapsedNow - base.elapsed)
        return deviceWall - expected
    }

    fun isTamper(offsetMs: Long): Boolean = abs(offsetMs) >= TAMPER_THRESHOLD_MS

    /**
     * Dopo un riavvio non c'è tempo monotòno da confrontare: se l'orologio segna un istante
     * PRECEDENTE all'ultima ora attendibile salvata, è stato portato indietro a telefono spento.
     */
    fun wentBackAcrossReboot(lastTrueWall: Long, deviceWall: Long): Boolean =
        lastTrueWall - deviceWall >= TAMPER_THRESHOLD_MS

    /**
     * Tempo residuo di un blocco (PIN errati). Nello stesso avvio conta solo il tempo monotòno:
     * spostare l'orologio non accorcia il blocco. Dopo un riavvio si usa l'orologio, ma limitato
     * alla durata del blocco: portarlo indietro non lo allunga all'infinito.
     */
    fun lockoutRemaining(
        sameBoot: Boolean,
        elapsedUntil: Long,
        elapsedNow: Long,
        wallUntil: Long,
        wallNow: Long,
        durationMs: Long,
    ): Long {
        if (durationMs <= 0) return 0
        return if (sameBoot) {
            (elapsedUntil - elapsedNow).coerceIn(0, durationMs)
        } else {
            (wallUntil - wallNow).coerceIn(0, durationMs)
        }
    }

    /** "3 ore e 20 minuti", "45 minuti", "2 giorni e 4 ore", "4 anni e 2 mesi". */
    fun formatDuration(ms: Long): String {
        val minutes = abs(ms) / 60_000
        if (minutes < 1) return "meno di un minuto"
        val hours = minutes / 60
        val days = hours / 24
        fun unit(n: Long, one: String, many: String) = if (n == 1L) "1 $one" else "$n $many"
        return when {
            days >= 365 -> {
                val years = days / 365
                val months = (days % 365) / 30
                unit(years, "anno", "anni") + if (months > 0) " e " + unit(months, "mese", "mesi") else ""
            }
            days >= 30 -> {
                val months = days / 30
                val rest = days % 30
                unit(months, "mese", "mesi") + if (rest > 0) " e " + unit(rest, "giorno", "giorni") else ""
            }
            days >= 1 -> unit(days, "giorno", "giorni") + if (hours % 24 > 0) " e " + unit(hours % 24, "ora", "ore") else ""
            hours >= 1 -> unit(hours, "ora", "ore") + if (minutes % 60 > 0) " e " + unit(minutes % 60, "minuto", "minuti") else ""
            else -> unit(minutes, "minuto", "minuti")
        }
    }

    /** Sotto questa durata un riavvio è considerato normale (nessuna nota sulla durata). */
    const val NOTABLE_DOWNTIME_MS = 2 * 60_000L

    /** "Telefono riavviato (era spento o in Modalità Provvisoria da 3 ore e 20 minuti)". */
    fun rebootTitle(downtimeMs: Long?): String =
        if (downtimeMs == null || downtimeMs < NOTABLE_DOWNTIME_MS) "Telefono riavviato"
        else "Telefono riavviato (era spento o in Modalità Provvisoria da ${formatDuration(downtimeMs)})"

    /** "in avanti di 4 anni e 2 mesi" / "indietro di 3 ore". */
    fun describeOffset(offsetMs: Long): String =
        (if (offsetMs >= 0) "in avanti di " else "indietro di ") + formatDuration(offsetMs)
}
