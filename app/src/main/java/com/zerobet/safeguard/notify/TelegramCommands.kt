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
    /** Foto (o immagine inviata come file) con la sua didascalia: va ai pannelli oscuranti. */
    data class Photo(val fileId: String, val caption: String) : RemoteCommand()
    /** Immagine oltre gli 8 MB o file che non è un'immagine. */
    data class PhotoRejected(val reason: String) : RemoteCommand()
    /** "/frase <testo>"; testo vuoto = nessuna frase. */
    data class SetCaption(val text: String) : RemoteCommand()
    data object PhotoStatus : RemoteCommand()
    data object ClearPhotos : RemoteCommand()
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
            "frase", "caption" -> when {
                argument.isEmpty() -> RemoteCommand.MissingArgument("/frase")
                argument.lowercase() in CAPTION_OFF -> RemoteCommand.SetCaption("")
                else -> RemoteCommand.SetCaption(argument)
            }
            "foto", "photos" -> RemoteCommand.PhotoStatus
            "cancella_foto", "cancellafoto", "delete_photos" -> RemoteCommand.ClearPhotos
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
            // Foto o immagine inviata come file: la didascalia diventa la frase dei pannelli.
            val photo = photoCommand(message)
            if (photo != null) {
                commands += IncomingCommand(updateId, photo, "[foto] " + message.optString("caption", "").take(MAX_ARGUMENT))
                continue
            }
            val command = parse(text) ?: continue
            commands += IncomingCommand(updateId, command, text.trim().take(MAX_ARGUMENT + 20))
        }
        return PollResult(commands, foreign.distinct(), if (maxId >= 0) maxId + 1 else null)
    }

    /** Dimensione massima di un'immagine scaricata da Telegram. */
    const val MAX_PHOTO_BYTES = 8L * 1024 * 1024
    private val CAPTION_OFF = setOf("vuota", "off", "nessuna", "no", "none", "-")

    /**
     * Foto di un messaggio: tra le dimensioni offerte da Telegram si sceglie la più grande entro
     * 8 MB; un documento conta solo se è un'immagine (mime image/...). null se non è una foto.
     */
    fun photoCommand(message: JSONObject): RemoteCommand? {
        val caption = message.optString("caption", "").trim().take(MAX_ARGUMENT)
        message.optJSONArray("photo")?.let { sizes ->
            var bestId: String? = null
            var bestArea = -1L
            for (i in 0 until sizes.length()) {
                val s = sizes.optJSONObject(i) ?: continue
                val size = s.optLong("file_size", 0L)
                if (size > MAX_PHOTO_BYTES) continue
                val area = s.optLong("width", 0L) * s.optLong("height", 0L)
                if (area > bestArea && s.optString("file_id").isNotEmpty()) {
                    bestArea = area
                    bestId = s.optString("file_id")
                }
            }
            return bestId?.let { RemoteCommand.Photo(it, caption) }
                ?: RemoteCommand.PhotoRejected("la foto supera gli 8 MB")
        }
        val document = message.optJSONObject("document") ?: return null
        val mime = document.optString("mime_type", "")
        if (!mime.startsWith("image/")) return RemoteCommand.PhotoRejected("il file non è un'immagine")
        if (document.optLong("file_size", 0L) > MAX_PHOTO_BYTES) return RemoteCommand.PhotoRejected("l'immagine supera gli 8 MB")
        val id = document.optString("file_id")
        return if (id.isEmpty()) null else RemoteCommand.Photo(id, caption)
    }

    fun photoAddedReply(count: Int, max: Int, caption: String): String =
        "🖼️ Foto aggiunta ai pannelli oscuranti ($count/$max attive) · Frase: " +
            if (caption.isBlank()) "nessuna (scritta standard)" else "\"$caption\""

    fun photoStatusReply(enabled: Boolean, count: Int, max: Int, caption: String): String = buildString {
        append("🖼️ Pannelli con le foto: ").append(if (enabled && count > 0) "attivi" else if (count == 0) "nessuna foto (pannello scuro)" else "spenti")
        append("\n📷 Foto: ").append(count).append('/').append(max)
        append("\n💬 Frase: ").append(if (caption.isBlank()) "nessuna" else "\"$caption\"")
        append("\n\nInvia una foto in questa chat per aggiungerla (la didascalia diventa la frase).")
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
        🤖 Comandi di CutBet (solo da questa chat):
        /stato — stato della protezione, batteria, blocchi delle ultime 24 ore
        /ultimi — ultimi 10 eventi del Registro Allarmi
        /blocca <dominio o parola> — blocca un sito (es. /blocca nuovosito.com) o una parola chiave
        /consenti <dominio> — consente un sito (es. /consenti miabanca.it)
        /foto — foto e frase dei pannelli oscuranti
        /frase <testo> — frase sui pannelli (/frase off per toglierla)
        /cancella_foto — toglie tutte le foto (torna il pannello scuro)
        Invia una foto con didascalia per aggiungerla ai pannelli (max 10, le più vecchie escono)
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
