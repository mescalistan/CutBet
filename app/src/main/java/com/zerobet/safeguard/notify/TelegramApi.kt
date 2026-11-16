package com.zerobet.safeguard.notify

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import com.zerobet.safeguard.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** Esito di sendMessage. */
sealed class SendResult {
    data object Ok : SendResult()
    data class RetryAfter(val seconds: Int) : SendResult()
    /** [permanent] = token/chat non validi: riprovare non serve finché il tutore non corregge. */
    data class Failed(val code: Int, val description: String, val permanent: Boolean) : SendResult()
    data class NetworkError(val message: String) : SendResult()
}

/** Esito di getUpdates. */
sealed class UpdatesResult {
    data class Ok(val body: String) : UpdatesResult()
    data class Failed(val code: Int, val description: String) : UpdatesResult()
    data class NetworkError(val message: String) : UpdatesResult()
}

/** Chat del tutore trovata tra i messaggi ricevuti dal bot. */
data class TelegramChat(val id: Long, val firstName: String, val username: String?) {
    val displayName: String
        get() = when {
            firstName.isNotBlank() && !username.isNullOrBlank() -> "$firstName (@$username)"
            firstName.isNotBlank() -> firstName
            !username.isNullOrBlank() -> "@$username"
            else -> "chat $id"
        }
}

/** Le due chiamate dell'API dei bot usate da SafeGuard. */
interface TelegramTransport {
    fun sendMessage(token: String, chatId: Long, text: String): SendResult
    /** [offset]: primo update_id da restituire (conferma i precedenti); [timeoutSec] > 0 = long polling. */
    fun getUpdates(token: String, offset: Long? = null, timeoutSec: Int = 0): UpdatesResult
    /** Scarica un file inviato al bot (getFile + download), al massimo [maxBytes]. */
    fun downloadFile(token: String, fileId: String, maxBytes: Long): DownloadResult
}

/** Esito del download di un file inviato al bot. */
sealed class DownloadResult {
    class Ok(val bytes: ByteArray) : DownloadResult()
    data class Failed(val description: String) : DownloadResult()
}

/**
 * Bot API di Telegram (BuildConfig.TELEGRAM_API_BASE, di norma https://api.telegram.org) con il
 * solo HttpURLConnection della piattaforma: nessuna libreria di rete
 * aggiuntiva nell'APK. Le funzioni di parsing sono pure e coperte da unit test.
 *
 * Il token non compare mai nei log: gli errori riportano solo codice e descrizione di Telegram.
 */
object TelegramApi : TelegramTransport {

    const val HOST = "api.telegram.org"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Formato del token di @BotFather: `<id numerico>:<35 caratteri circa>`. */
    private val TOKEN = Regex("^\\d{5,12}:[A-Za-z0-9_-]{30,64}$")

    fun isValidToken(token: String): Boolean = TOKEN.matches(token.trim())

    fun parseChatId(raw: String): Long? = raw.trim().toLongOrNull()?.takeIf { it != 0L }

    override fun sendMessage(token: String, chatId: Long, text: String): SendResult {
        val form = "chat_id=$chatId&disable_web_page_preview=true&text=" + URLEncoder.encode(text, "UTF-8")
        return when (val r = request(token, "sendMessage", form)) {
            is HttpResponse.Error -> SendResult.NetworkError(r.message)
            is HttpResponse.Body -> parseSendResponse(r.code, r.body)
        }
    }

    override fun getUpdates(token: String, offset: Long?, timeoutSec: Int): UpdatesResult {
        val query = buildString {
            append("getUpdates?limit=").append(if (offset == null && timeoutSec == 0) 10 else 20)
            if (offset != null) append("&offset=").append(offset)
            if (timeoutSec > 0) append("&timeout=").append(timeoutSec).append("&allowed_updates=%5B%22message%22%5D")
        }
        val readTimeout = if (timeoutSec > 0) (timeoutSec + 15) * 1000 else READ_TIMEOUT_MS
        return when (val r = request(token, query, null, readTimeout)) {
            is HttpResponse.Error -> UpdatesResult.NetworkError(r.message)
            is HttpResponse.Body ->
                if (r.code == 200 && isOk(r.body)) UpdatesResult.Ok(r.body)
                else UpdatesResult.Failed(r.code, describe(r.code, r.body))
        }
    }

    // -------------------------------------------------------------------------------------------
    // Parsing (puro)
    // -------------------------------------------------------------------------------------------

    fun parseSendResponse(code: Int, body: String?): SendResult {
        if (code == 200 && isOk(body)) return SendResult.Ok
        if (code == 429) {
            val retry = try {
                JSONObject(body ?: "").optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0
            } catch (e: JSONException) {
                0
            }
            return SendResult.RetryAfter(retry.coerceIn(1, 3_600))
        }
        // 400 (chat inesistente), 401 (token revocato), 403 (bot bloccato dal tutore), 404 (token errato).
        val permanent = code in 400..499
        return SendResult.Failed(code, describe(code, body), permanent)
    }

    /**
     * Cerca, tra gli aggiornamenti restituiti da getUpdates, la chat del tutore: l'ultimo messaggio
     * "/start" ricevuto dal bot; in mancanza, l'ultimo messaggio di una chat privata.
     */
    fun parseStartChat(body: String): TelegramChat? {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return null
        }
        if (!root.optBoolean("ok", false)) return null
        val updates = root.optJSONArray("result") ?: return null
        var start: TelegramChat? = null
        var privateChat: TelegramChat? = null
        for (i in 0 until updates.length()) {
            val update = updates.optJSONObject(i) ?: continue
            val message = update.optJSONObject("message") ?: update.optJSONObject("edited_message") ?: continue
            val chat = message.optJSONObject("chat") ?: continue
            if (!chat.has("id")) continue
            val from = message.optJSONObject("from")
            val found = TelegramChat(
                id = chat.optLong("id"),
                firstName = listOf(
                    chat.optString("first_name", from?.optString("first_name", "") ?: ""),
                    chat.optString("last_name", ""),
                ).filter { it.isNotBlank() }.joinToString(" "),
                username = (chat.optString("username", "").ifBlank { from?.optString("username", "") ?: "" }).ifBlank { null },
            )
            val text = message.optString("text", "")
            if (text.trim().startsWith("/start")) start = found
            if (chat.optString("type") == "private") privateChat = found
        }
        return start ?: privateChat
    }

    private fun isOk(body: String?): Boolean = try {
        JSONObject(body ?: "").optBoolean("ok", false)
    } catch (e: JSONException) {
        false
    }

    private fun describe(code: Int, body: String?): String {
        val description = try {
            JSONObject(body ?: "").optString("description", "")
        } catch (e: JSONException) {
            ""
        }
        val hint = when (code) {
            401, 404 -> "token non valido o revocato"
            400 -> "chat non trovata: apri la chat del bot e premi Avvia"
            403 -> "il bot è stato bloccato su Telegram"
            409 -> "il bot ha un webhook attivo: rimuovilo per usare il collegamento automatico"
            else -> ""
        }
        return listOf("HTTP $code", hint, description).filter { it.isNotBlank() }.joinToString(" · ")
    }

    // -------------------------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------------------------

    private sealed class HttpResponse {
        class Body(val code: Int, val body: String) : HttpResponse()
        class Error(val message: String) : HttpResponse()
    }

    override fun downloadFile(token: String, fileId: String, maxBytes: Long): DownloadResult {
        val info = request(token, "getFile?file_id=" + URLEncoder.encode(fileId, "UTF-8"), null)
        if (info !is HttpResponse.Body || info.code != 200) return DownloadResult.Failed("file non disponibile")
        val path = parseFilePath(info.body, maxBytes) ?: return DownloadResult.Failed("file troppo grande o non disponibile")
        val connection = try {
            URL("${BuildConfig.TELEGRAM_API_BASE}/file/bot$token/$path").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return DownloadResult.Failed(e.javaClass.simpleName)
        }
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            if (connection.responseCode != 200) return DownloadResult.Failed("HTTP ${connection.responseCode}")
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) return DownloadResult.Failed("file oltre gli 8 MB")
                }
            }
            DownloadResult.Ok(out.toByteArray())
        } catch (e: IOException) {
            DownloadResult.Failed(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    /** Percorso del file da getFile, se esiste e non supera [maxBytes]. */
    fun parseFilePath(body: String, maxBytes: Long): String? = try {
        val result = JSONObject(body).takeIf { it.optBoolean("ok") }?.optJSONObject("result")
        val size = result?.optLong("file_size", 0L) ?: 0L
        result?.optString("file_path")?.takeIf { it.isNotBlank() && size <= maxBytes && !it.contains("..") }
    } catch (e: JSONException) {
        null
    }

    private fun request(token: String, method: String, form: String?, readTimeoutMs: Int = READ_TIMEOUT_MS): HttpResponse {
        val connection = try {
            URL("${BuildConfig.TELEGRAM_API_BASE}/bot$token/$method").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return HttpResponse.Error(e.javaClass.simpleName)
        }
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(MAX_BODY) } ?: ""
            HttpResponse.Body(code, body)
        } catch (e: IOException) {
            // Il messaggio dell'eccezione può contenere l'URL (e quindi il token): si usa solo il tipo.
            HttpResponse.Error(e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private const val MAX_BODY = 256 * 1024
}
