package com.zerobet.safeguard.notify

import com.zerobet.safeguard.data.SecurityEventLog
import com.zerobet.safeguard.data.Severity
import com.zerobet.safeguard.security.ClockGuard
import org.json.JSONException
import org.json.JSONObject
import java.time.ZoneId

/** Comando scritto dal tutore nella chat del bot. */
sealed class RemoteCommand {
    data object Status : RemoteCommand()
    data object Latest : RemoteCommand()
    data object Help : RemoteCommand()
    data class Block(val target: String) : RemoteCommand()
    data class Allow(val target: String) : RemoteCommand()
    /** Comando che richiede un argomento scritto senza (es. "/blocca"). */
    data class MissingArgument(val command: String) : RemoteCommand()
    data class Unknown(val command: String) : RemoteCommand()
}

data class IncomingCommand(val updateId: Long, val command: RemoteCommand, val raw: String)

/**
 * Esito della lettura di getUpdates: i comandi del tutore, i messaggi arrivati da altre chat
 * (ignorati) e l'offset da usare alla richiesta successiva, così nessun aggiornamento viene
 * elaborato due volte.
 */
data class PollResult(val commands: List<IncomingCommand>, val foreignChats: List<Long>, val nextOffset: Long?)

/**
 * Comandi remoti del tutore via bot Telegram. Kotlin puro, coperto da unit test.
 *
 * Sicurezza: si accettano SOLO i messaggi della chat collegata (chat_id salvato nelle preferenze
 * cifrate). Chiunque altro scriva al bot viene ignorato: il suo chat_id finisce solo nel registro.
 * I messaggi modificati (edited_message) non vengono eseguiti, per non ripetere un comando.
 */
object TelegramCommands {

    const val MAX_ARGUMENT = 120

    /** null se il testo non è un comando (non inizia con "/"). */
    fun parse(text: String): RemoteCommand? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/") || trimmed.length < 2) return null
        val head = trimmed.substringBefore(' ').substringBefore('\n')
        // "/stato@MioCutBet_bot": il nome del bot dopo la @ si ignora.
        val name = head.drop(1).substringBefore('@').lowercase()
        val argument = trimmed.drop(head.length).trim().take(MAX_ARGUMENT)
        return when (name) {
            "stato", "report", "status" -> RemoteCommand.Status
            "ultimi", "eventi", "registro" -> RemoteCommand.Latest
            "aiuto", "help", "start", "comandi" -> RemoteCommand.Help
            "blocca", "block" -> if (argument.isEmpty()) RemoteCommand.MissingArgument("/blocca") else RemoteCommand.Block(argument)
            "consenti", "allow" -> if (argument.isEmpty()) RemoteCommand.MissingArgument("/consenti") else RemoteCommand.Allow(argument)
            else -> RemoteCommand.Unknown("/$name")
        }
    }

    fun parseUpdates(body: String, authorizedChatId: Long): PollResult? {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return null
        }
        if (!root.optBoolean("ok", false)) return null
        val updates = root.optJSONArray("result") ?: return PollResult(emptyList(), emptyList(), null)
        val commands = ArrayList<IncomingCommand>()
        val foreign = ArrayList<Long>()
        var maxId = -1L
        for (i in 0 until updates.length()) {
            val update = updates.optJSONObject(i) ?: continue
            val updateId = update.optLong("update_id", -1)
            if (updateId > maxId) maxId = updateId
            val message = update.optJSONObject("message") ?: continue
            val chatId = message.optJSONObject("chat")?.optLong("id", 0L) ?: 0L
            val text = message.optString("text", "")
            if (chatId != authorizedChatId) {
                if (chatId != 0L) foreign += chatId
                continue
            }
            val command = parse(text) ?: continue
            commands += IncomingCommand(updateId, command, text.trim().take(MAX_ARGUMENT + 20))
        }
        return PollResult(commands, foreign.distinct(), if (maxId >= 0) maxId + 1 else null)
    }

    /** Offset successivo all'ultimo aggiornamento presente (null se non ce ne sono). */
    fun nextOffset(body: String): Long? = try {
        val updates = JSONObject(body).optJSONArray("result")
        var maxId = -1L
        if (updates != null) for (i in 0 until updates.length()) {
            val id = updates.optJSONObject(i)?.optLong("update_id", -1) ?: -1
            if (id > maxId) maxId = id
        }
        if (maxId >= 0) maxId + 1 else null
    } catch (e: JSONException) {
        null
    }

    val HELP_TEXT = """
        🤖 Comandi di SafeGuard (solo da questa chat):
        /stato — stato della protezione, batteria, blocchi delle ultime 24 ore
        /ultimi — ultimi 10 eventi del Registro Allarmi
        /blocca <dominio o parola> — blocca un sito (es. /blocca nuovosito.com) o una parola chiave
        /consenti <dominio> — consente un sito (es. /consenti miabanca.it)
        /aiuto — questo elenco
    """.trimIndent()

    /** Dati del report /stato. */
    data class DeviceStatus(
        val vpnOk: Boolean,
        val protectionPaused: Boolean,
        val guardOk: Boolean,
        val uptimeMs: Long,
        val batteryPercent: Int?,
        val charging: Boolean,
        val domains: Int,
        val searches: Int,
        val settingsAttempts: Int,
        val lastListsUpdate: Long?,
        val pendingAlerts: Int,
        val clockOffsetMs: Long,
    )

    fun formatStatus(s: DeviceStatus, now: Long, zone: ZoneId): String = buildString {
        append("📊 Stato di CutBet\n")
        append("🛡 Filtro VPN: ").append(
            when {
                s.protectionPaused -> "IN PAUSA (dal tutore)"
                s.vpnOk -> "attivo"
                else -> "SPENTO"
            }
        ).append('\n')
        append("👁 Guardiano Impostazioni: ").append(if (s.guardOk) "attivo" else "SPENTO").append('\n')
        append("⏱ Acceso da: ").append(ClockGuard.formatDuration(s.uptimeMs))
        append(" (ultimo riavvio ").append(AlertPolicy.dateTime(now - s.uptimeMs, zone).substring(0, 16)).append(")\n")
        append("🔋 Batteria: ").append(s.batteryPercent?.let { "$it%" } ?: "non disponibile")
        if (s.charging) append(" (in carica)")
        append('\n')
        append("🚫 Ultime 24h: ${s.domains} domini, ${s.searches} ricerche, ")
        append(if (s.settingsAttempts == 1) "1 tentativo impostazioni" else "${s.settingsAttempts} tentativi impostazioni").append('\n')
        val last = s.lastListsUpdate
        append("📋 Liste esterne: ").append(
            when {
                last == null -> "mai aggiornate"
                now - last > AlertPolicy.LISTS_STALE_MS -> "NON aggiornate dal ${AlertPolicy.dateTime(last, zone).substring(0, 10)}"
                else -> "aggiornate il ${AlertPolicy.dateTime(last, zone).substring(0, 16)}"
            }
        ).append('\n')
        if (ClockGuard.isTamper(s.clockOffsetMs)) {
            append("⚠️ Orologio del telefono manomesso (").append(ClockGuard.describeOffset(s.clockOffsetMs)).append(")\n")
        }
        if (s.pendingAlerts > 0) append("📬 Avvisi in coda: ${s.pendingAlerts}\n")
        append("🕒 ").append(AlertPolicy.dateTime(now, zone))
    }

    fun formatLatest(events: List<SecurityEventLog>, zone: ZoneId): String {
        if (events.isEmpty()) return "🗂 Il Registro Allarmi è vuoto."
        return buildString {
            append("🗂 Ultimi ${events.size} eventi del Registro Allarmi:")
            events.forEach { e ->
                append("\n\n")
                append(
                    when (e.severity) {
                        Severity.CRITICAL -> "🚨 "
                        Severity.WARNING -> "⚠️ "
                        else -> "ℹ️ "
                    }
                )
                append(AlertPolicy.dateTime(e.timestamp, zone).substring(0, 16)).append(' ')
                append(e.title.take(120))
                if (e.count > 1) append(" (x").append(e.count).append(')')
                if (e.detail.isNotBlank()) append("\n   ").append(e.detail.take(140))
            }
        }
    }

    /** "/blocca": un argomento con un punto è un dominio, altrimenti una parola chiave. */
    fun isDomainTarget(target: String): Boolean = target.trim().contains('.')
}
