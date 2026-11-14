package com.zerobet.safeguard.notify

import android.content.Context
import android.util.Log
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.security.DeviceStorage
import com.zerobet.safeguard.security.TrustedClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/**
 * Comandi remoti del tutore: legge i messaggi del bot con getUpdates in long polling (una
 * richiesta aperta fino a [LONG_POLL_S] secondi, risposta immediata appena arriva un comando) e
 * risponde. L'offset viene salvato su disco dopo ogni lettura: un comando non viene mai eseguito
 * due volte, nemmeno dopo un riavvio.
 *
 * Solo la chat collegata può comandare (vedi [TelegramCommands]).
 */
class TelegramCommandPoller(
    private val context: Context,
    private val transport: TelegramTransport,
    private val updatesLock: Mutex,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private val app get() = context.applicationContext as SafeGuardApp

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    var offset: Long?
        get() = DeviceStorage.statePrefs(context).getLong(KEY_OFFSET, -1L).takeIf { it >= 0 }
        set(value) {
            DeviceStorage.statePrefs(context).edit().apply {
                if (value == null) remove(KEY_OFFSET) else putLong(KEY_OFFSET, value)
            }.commit()
        }

    private suspend fun loop() {
        while (scope.isActive) {
            val s = app.telegram.config.snapshot
            val token = s.token
            val chatId = s.chatId
            if (!s.ready || token == null || chatId == null) {
                delay(IDLE_MS)
                continue
            }
            val result = updatesLock.withLock { transport.getUpdates(token, offset, LONG_POLL_S) }
            when (result) {
                is UpdatesResult.Ok -> handle(result.body, token, chatId)
                is UpdatesResult.NetworkError -> delay(OFFLINE_RETRY_MS)
                // 409: un'altra lettura in corso (o un webhook); 401/404: token revocato.
                is UpdatesResult.Failed -> delay(if (result.code == 409) CONFLICT_RETRY_MS else ERROR_RETRY_MS)
            }
        }
    }

    private suspend fun handle(body: String, token: String, chatId: Long) {
        val poll = TelegramCommands.parseUpdates(body, chatId) ?: return
        // Prima si avanza l'offset, poi si esegue: un crash a metà non ripete il comando.
        poll.nextOffset?.let { offset = it }
        poll.foreignChats.forEach { foreign ->
            app.securityLog.warning(
                EventType.REMOTE, "Messaggio al bot da una chat non autorizzata ignorato",
                "Chat ID $foreign: solo la chat del tutore può inviare comandi"
            )
        }
        for (incoming in poll.commands) {
            val reply = try {
                execute(incoming.command)
            } catch (e: Exception) {
                Log.w(TAG, "Comando non eseguito: ${e.javaClass.simpleName}")
                "❌ Errore nell'esecuzione del comando."
            }
            app.securityLog.info(EventType.REMOTE, "Comando remoto dal tutore: ${incoming.raw.take(60)}", reply.lineSequence().first().take(120))
            val sent = transport.sendMessage(token, chatId, reply)
            if (sent !is SendResult.Ok) app.telegram.enqueue(QueuedAlert(TrustedClock.now(), reply))
        }
    }

    suspend fun execute(command: RemoteCommand): String = when (command) {
        is RemoteCommand.Status -> TelegramCommands.formatStatus(app.telegram.deviceStatus(), TrustedClock.now(), zone)
        is RemoteCommand.Latest -> TelegramCommands.formatLatest(app.database.securityEventDao().latest(10), zone)
        is RemoteCommand.Help -> TelegramCommands.HELP_TEXT
        is RemoteCommand.Block -> {
            val target = command.target.trim()
            val domain = TelegramCommands.isDomainTarget(target)
            if (app.repository.addBlocked(target, isKeyword = !domain)) {
                "🚫 Bloccato: $target" + if (domain) " (dominio e sottodomini)" else " (parola chiave)"
            } else {
                "❌ Non valido: \"$target\". Esempi: /blocca nuovosito.com oppure /blocca scommesse"
            }
        }
        is RemoteCommand.Allow -> {
            val target = command.target.trim()
            if (TelegramCommands.isDomainTarget(target) && app.repository.addWhitelisted("Consentito da Telegram", target)) {
                "✅ Consentito: $target"
            } else {
                "❌ Dominio non valido: \"$target\". Esempio: /consenti miabanca.it"
            }
        }
        is RemoteCommand.MissingArgument -> "ℹ️ Manca il dominio o la parola: ${command.command} <dominio>"
        is RemoteCommand.Unknown -> "❓ Comando ${command.command} non riconosciuto.\n\n" + TelegramCommands.HELP_TEXT
    }

    private companion object {
        const val TAG = "SafeGuardCommands"
        const val KEY_OFFSET = "telegram_updates_offset"
        const val LONG_POLL_S = 50
        const val IDLE_MS = 30_000L
        const val OFFLINE_RETRY_MS = 30_000L
        const val CONFLICT_RETRY_MS = 60_000L
        const val ERROR_RETRY_MS = 5 * 60_000L
    }
}
