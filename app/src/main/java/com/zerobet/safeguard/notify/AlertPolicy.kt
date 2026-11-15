package com.zerobet.safeguard.notify

import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.data.Severity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Livelli di dettaglio scelti dal tutore per gli avvisi su Telegram. */
enum class AlertChannel {
    /** SIM, riavvii, PIN errati, tentativi sulle Impostazioni protette, servizi spenti: subito. */
    CRITICAL,
    /** Ricerche bloccate, app vietate aperte, pagine/video/post bloccati: aggregati. */
    BLOCKS,
    /** Domini bloccati dal filtro DNS e pubblicità: aggregati, disattivati di default. */
    DOMAINS,
}

data class AlertPrefs(
    val critical: Boolean = true,
    val blocks: Boolean = true,
    val domains: Boolean = false,
    /** Report giornaliero di stato ("battito cardiaco"). */
    val heartbeat: Boolean = true,
) {
    fun allows(channel: AlertChannel): Boolean = when (channel) {
        AlertChannel.CRITICAL -> critical
        AlertChannel.BLOCKS -> blocks
        AlertChannel.DOMAINS -> domains
    }
}

/**
 * Regole e testi degli avvisi Telegram. Kotlin puro, coperto da unit test.
 *
 * I messaggi sono testo semplice (niente HTML/Markdown): titoli e dettagli arrivano da pagine e
 * ricerche dell'utente e non devono poter rompere la formattazione.
 */
object AlertPolicy {

    /** Oltre questo ritardo l'avviso dice che è stato trattenuto perché il telefono era offline. */
    const val LATE_AFTER_MS = 2 * 60_000L

    /** Limite pratico di un messaggio (Telegram accetta 4096 caratteri). */
    const val MAX_MESSAGE_CHARS = 3_800

    fun channelOf(type: String, title: String): AlertChannel? = when (type) {
        EventType.TAMPER, EventType.SIM, EventType.PIN, EventType.POWER, EventType.INTEGRITY -> AlertChannel.CRITICAL
        EventType.SEARCH, EventType.APP -> AlertChannel.BLOCKS
        EventType.CONTENT -> if (title.startsWith("Pubblicit", ignoreCase = true)) AlertChannel.DOMAINS else AlertChannel.BLOCKS
        EventType.DOMAIN -> AlertChannel.DOMAINS
        else -> null
    }

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    fun time(timestamp: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(timestamp).atZone(zone))
    fun dateTime(timestamp: Long, zone: ZoneId): String = DATE_TIME.format(Instant.ofEpochMilli(timestamp).atZone(zone))

    /** Avviso immediato (canale CRITICAL). */
    fun formatImmediate(severity: String, title: String, detail: String, timestamp: Long, zone: ZoneId): String =
        buildString {
            append(
                when (severity) {
                    Severity.CRITICAL -> "🚨 CutBet · ALLARME CRITICO"
                    Severity.WARNING -> "⚠️ CutBet · Attenzione"
                    else -> "ℹ️ CutBet"
                }
            )
            append('\n').append(title.trim())
            if (detail.isNotBlank()) append('\n').append(detail.trim().take(300))
            append("\n🕒 ").append(dateTime(timestamp, zone))
        }

    /** Riga di un messaggio aggregato di blocchi. */
    fun formatBlockLine(item: BlockItem, zone: ZoneId): String = buildString {
        append("• ").append(time(item.timestamp, zone)).append(' ').append(item.title.trim().take(160))
        if (item.count > 1) append(" (x").append(item.count).append(')')
        if (item.detail.isNotBlank()) append("\n   ").append(item.detail.trim().take(160))
    }

    /**
     * Raggruppa le voci della coda (già in ordine cronologico) in messaggi di al massimo
     * [maxChars] caratteri. Ogni messaggio porta il numero di voci che contiene, così dopo un
     * invio riuscito si rimuovono dalla coda esattamente quelle.
     */
    fun pack(entries: List<QueuedAlert>, now: Long, zone: ZoneId, maxChars: Int = MAX_MESSAGE_CHARS): List<PackedMessage> {
        val out = ArrayList<PackedMessage>()
        var i = 0
        while (i < entries.size) {
            val texts = ArrayList<String>()
            var length = 0
            var j = i
            while (j < entries.size) {
                val text = decorate(entries[j], now, zone).take(maxChars - HEADER_RESERVE)
                val extra = text.length + 2
                if (texts.isNotEmpty() && length + extra > maxChars - HEADER_RESERVE) break
                texts += text
                length += extra
                j++
            }
            val body = if (texts.size == 1) {
                texts[0]
            } else {
                "📬 ${texts.size} avvisi di CutBet, in ordine cronologico:\n\n" + texts.joinToString("\n\n")
            }
            out += PackedMessage(count = j - i, text = body)
            i = j
        }
        return out
    }

    private const val HEADER_RESERVE = 80

    private fun decorate(entry: QueuedAlert, now: Long, zone: ZoneId): String =
        if (now - entry.timestamp > LATE_AFTER_MS) {
            entry.text + "\n⏱ Inviato in ritardo: in quel momento il telefono era offline."
        } else {
            entry.text
        }

    /** Liste esterne più vecchie di così: il report lo segnala. */
    const val LISTS_STALE_MS = 3 * 24 * 60 * 60_000L

    /** Dati del report giornaliero. */
    data class HeartbeatStats(
        val vpnOk: Boolean,
        val guardOk: Boolean,
        val domains: Int,
        val searches: Int,
        val settingsAttempts: Int,
        val lastListsUpdate: Long?,
    )

    fun formatHeartbeat(stats: HeartbeatStats, now: Long, zone: ZoneId): String = buildString {
        val ok = stats.vpnOk && stats.guardOk
        append(if (ok) "💓 CutBet Attivo" else "⚠️ CutBet attivo ma INCOMPLETO")
        append(" · Protezione VPN e Guardiano: ")
        append(
            when {
                ok -> "OK"
                !stats.vpnOk && !stats.guardOk -> "VPN e Guardiano SPENTI"
                !stats.vpnOk -> "VPN SPENTA"
                else -> "Guardiano SPENTO"
            }
        )
        append(" · Blocchi ultime 24h: ${stats.domains} domini, ${stats.searches} ricerche, ")
        append(if (stats.settingsAttempts == 1) "1 tentativo impostazioni" else "${stats.settingsAttempts} tentativi impostazioni")
        val last = stats.lastListsUpdate
        append(
            when {
                last == null -> " · Liste esterne mai aggiornate."
                now - last > LISTS_STALE_MS -> " · Liste NON aggiornate dal ${dateTime(last, zone).substring(0, 10)}."
                else -> " · Liste aggiornate."
            }
        )
        append("\n🕒 ").append(dateTime(now, zone))
    }

    /** Messaggio di conferma inviato quando il bot viene collegato. */
    const val CONNECTED_MESSAGE = "✅ CutBet collegato correttamente! Riceverai qui gli avvisi di sicurezza."

    fun testMessage(timestamp: Long, zone: ZoneId): String =
        "🔔 Messaggio di prova di CutBet\nIl canale degli avvisi funziona.\n🕒 ${dateTime(timestamp, zone)}"
}

/** Voce della coda persistente: testo già formattato e istante in cui è avvenuto il fatto. */
data class QueuedAlert(val timestamp: Long, val text: String)

data class PackedMessage(val count: Int, val text: String)

/** Blocco in attesa di essere inviato nel messaggio aggregato. */
data class BlockItem(val timestamp: Long, val title: String, val detail: String, val count: Int = 1)

/**
 * Aggregazione dei blocchi: al massimo un messaggio ogni [windowMs] (default 60 s). Il primo
 * blocco dopo una pausa parte dopo [gatherMs] (così una raffica di eventi della stessa pagina
 * finisce in un solo messaggio); i successivi aspettano la fine della finestra.
 * Kotlin puro, coperto da unit test. Non thread-safe: chi lo usa sincronizza.
 */
class BlockAggregator(
    private val windowMs: Long = 60_000L,
    private val gatherMs: Long = 3_000L,
    private val maxLines: Int = 15,
) {
    private val pending = LinkedHashMap<String, BlockItem>()
    private var lastSentAt: Long = Long.MIN_VALUE / 2
    private var scheduledAt: Long? = null

    /** @return fra quanti ms chiamare [drain], oppure null se un invio è già pianificato. */
    fun add(item: BlockItem, now: Long): Long? {
        val key = item.title + '\u0000' + item.detail
        val existing = pending[key]
        pending[key] = if (existing == null) item else existing.copy(count = existing.count + item.count)
        if (scheduledAt != null) return null
        val at = maxOf(now + gatherMs, lastSentAt + windowMs)
        scheduledAt = at
        return at - now
    }

    val pendingCount: Int get() = pending.size

    /** Costruisce il messaggio con i blocchi accumulati (null se non ce ne sono). */
    fun drain(now: Long, zone: ZoneId): QueuedAlert? {
        scheduledAt = null
        if (pending.isEmpty()) return null
        val items = pending.values.toList()
        pending.clear()
        lastSentAt = now
        val total = items.sumOf { it.count }
        val text = buildString {
            append("🚫 CutBet · ")
            append(if (total == 1) "1 blocco" else "$total blocchi")
            append(" nell'ultimo minuto")
            items.take(maxLines).forEach { append('\n').append(AlertPolicy.formatBlockLine(it, zone)) }
            if (items.size > maxLines) append("\n…e altri ${items.size - maxLines}")
        }
        return QueuedAlert(items.first().timestamp, text)
    }
}
