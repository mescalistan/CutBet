package com.zerobet.safeguard.notify

import java.io.File
import java.io.IOException

/**
 * Coda persistente degli avvisi non ancora consegnati su Telegram (telefono offline, SIM rimossa,
 * modalità aereo, Telegram che chiede di rallentare).
 *
 * Un file di testo nello storage protetto dal dispositivo, una riga per avviso:
 * `timestamp<TAB>testo` con a capo e tabulazioni codificati. Ogni aggiunta è un append di poche
 * decine di byte; la rimozione delle voci consegnate riscrive il file in modo atomico
 * (file temporaneo + rename), così uno spegnimento a metà non perde la coda.
 *
 * Kotlin puro (solo java.io), coperto da unit test. Thread-safe.
 */
class AlertQueue(private val file: File, private val maxEntries: Int = MAX_ENTRIES) {

    @Synchronized
    fun add(alert: QueuedAlert) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(encode(alert) + "\n", Charsets.UTF_8)
            if (countLines() > maxEntries) {
                // Coda piena (es. token sbagliato per giorni): si tengono le voci più recenti.
                write(readAll().takeLast(maxEntries))
            }
        } catch (e: IOException) {
            // Disco pieno o non scrivibile: l'avviso resta comunque nel Registro Allarmi.
        }
    }

    /** Tutte le voci in ordine cronologico (dell'istante in cui è avvenuto il fatto). */
    @Synchronized
    fun all(): List<QueuedAlert> = readAll().sortedBy { it.timestamp }

    @Synchronized
    fun size(): Int = if (file.exists()) countLines() else 0

    /** Rimuove le prime [count] voci in ordine cronologico (quelle appena consegnate). */
    @Synchronized
    fun removeFirst(count: Int) {
        if (count <= 0) return
        write(all().drop(count))
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun readAll(): List<QueuedAlert> {
        if (!file.exists()) return emptyList()
        return try {
            file.readLines(Charsets.UTF_8).mapNotNull { decode(it) }
        } catch (e: IOException) {
            emptyList()
        }
    }

    private fun countLines(): Int = try {
        file.useLines(Charsets.UTF_8) { lines -> lines.count { it.isNotBlank() } }
    } catch (e: IOException) {
        0
    }

    private fun write(entries: List<QueuedAlert>) {
        if (entries.isEmpty()) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(entries.joinToString("") { encode(it) + "\n" }, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val MAX_ENTRIES = 300

        fun encode(alert: QueuedAlert): String = alert.timestamp.toString() + "\t" + escape(alert.text)

        fun decode(line: String): QueuedAlert? {
            val tab = line.indexOf('\t')
            if (tab <= 0) return null
            val ts = line.substring(0, tab).toLongOrNull() ?: return null
            return QueuedAlert(ts, unescape(line.substring(tab + 1)))
        }

        private fun escape(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }

        private fun unescape(s: String): String = buildString(s.length) {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        '\\' -> append('\\')
                        else -> append(s[i + 1])
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
    }
}

/** Esito di un tentativo di svuotare la coda. */
sealed class FlushOutcome {
    /** Coda vuota: tutto consegnato (o niente da consegnare). */
    data object Done : FlushOutcome()
    /** Notifiche non configurate o disattivate: la coda resta com'è. */
    data object NotConfigured : FlushOutcome()
    /** Rete assente o errore temporaneo: si riprova quando torna la connessione. */
    data class Offline(val reason: String) : FlushOutcome()
    /** HTTP 429: Telegram chiede di attendere. */
    data class RetryAfter(val delayMs: Long) : FlushOutcome()
    /** Token o chat non validi (401/400/403): serve l'intervento del tutore. */
    data class ConfigError(val description: String) : FlushOutcome()
}

/**
 * Svuota la coda in ordine cronologico. Kotlin puro: trasporto, orologio e attesa tra i
 * messaggi sono iniettati, così la logica (ordine, conservazione offline, 429) è testabile.
 */
class AlertDispatcher(
    private val queue: AlertQueue,
    private val transport: TelegramTransport,
    private val zone: java.time.ZoneId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun flush(token: String?, chatId: Long?): FlushOutcome {
        if (token.isNullOrBlank() || chatId == null) return FlushOutcome.NotConfigured
        var first = true
        while (true) {
            val entries = queue.all()
            if (entries.isEmpty()) return FlushOutcome.Done
            val message = AlertPolicy.pack(entries, clock(), zone).first()
            if (!first) pause(MESSAGE_SPACING_MS)   // Telegram: circa 1 messaggio al secondo per chat
            first = false
            when (val result = transport.sendMessage(token, chatId, message.text)) {
                is SendResult.Ok -> queue.removeFirst(message.count)
                is SendResult.RetryAfter -> return FlushOutcome.RetryAfter(result.seconds * 1000L)
                is SendResult.NetworkError -> return FlushOutcome.Offline(result.message)
                is SendResult.Failed ->
                    return if (result.permanent) FlushOutcome.ConfigError(result.description)
                    else FlushOutcome.Offline(result.description)
            }
        }
    }

    companion object {
        const val MESSAGE_SPACING_MS = 1_100L
    }
}
