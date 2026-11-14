package com.zerobet.safeguard.notify

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
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
    fun getUpdates(token: String): UpdatesResult
}

/**
 * Bot API di Telegram con il solo HttpsURLConnection della piattaforma: nessuna libreria di rete
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

    override fun getUpdates(token: String): UpdatesResult =
        when (val r = request(token, "getUpdates?limit=10", null)) {
            is HttpResponse.Error -> UpdatesResult.NetworkError(r.message)
            is HttpResponse.Body ->
                if (r.code == 200 && isOk(r.body)) UpdatesResult.Ok(r.body)
                else UpdatesResult.Failed(r.code, describe(r.code, r.body))
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

    private fun request(token: String, method: String, form: String?): HttpResponse {
        val connection = try {
            URL("https://$HOST/bot$token/$method").openConnection() as HttpsURLConnection
        } catch (e: IOException) {
            return HttpResponse.Error(e.javaClass.simpleName)
        }
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
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
