package com.zerobet.safeguard.notify

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.data.EventType
import com.zerobet.safeguard.data.SecurityEventLog
import com.zerobet.safeguard.guard.SettingsGuardAccessibilityService
import com.zerobet.safeguard.security.PowerHistory
import com.zerobet.safeguard.security.TrustedClock
import com.zerobet.safeguard.vpn.DomainBlockerVpnService
import com.zerobet.safeguard.security.DeviceStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/**
 * Avvisi remoti al tutore tramite un bot Telegram.
 *
 * Flusso: ogni nuova riga del Registro Allarmi passa da [onSecurityEvent].
 *  - Eventi critici (SIM, riavvii, PIN errati, Impostazioni protette, servizi spenti): formattati
 *    e scritti SUBITO nella coda persistente, poi si tenta l'invio.
 *  - Blocchi (ricerche, app, contenuti, domini): raccolti da [BlockAggregator], al massimo un
 *    messaggio al minuto, poi nella stessa coda.
 * La coda vive su disco nello storage protetto dal dispositivo: se il telefono è offline (SIM
 * appena rimossa, modalità aereo) gli avvisi restano lì con l'orario esatto del fatto e partono
 * in ordine cronologico appena torna una rete (NetworkCallback, più un lavoro WorkManager con
 * vincolo di rete come riserva quando lo storage dell'utente è disponibile).
 */
class TelegramNotifier(
    private val context: Context,
    private val transport: TelegramTransport = TelegramApi,
) {
    val config: TelegramConfig by lazy { TelegramConfig(context) }
    private val queue = AlertQueue(File(DeviceStorage.context(context).filesDir, QUEUE_FILE))
    private val zone: ZoneId get() = ZoneId.systemDefault()
    // Ora attendibile: con un orologio manomesso gli avvisi non risultano "in ritardo" per errore.
    private val dispatcher by lazy { AlertDispatcher(queue, transport, ZoneId.systemDefault(), clock = { TrustedClock.now() }) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushLock = Mutex()
    @Volatile private var flushAgain = false
    private val aggregator = BlockAggregator()
    private var retryJob: Job? = null
    @Volatile private var started = false

    private val _status = MutableStateFlow<String?>(null)
    /** Ultimo esito degli invii, mostrato nella sezione "Notifiche Telegram" della dashboard. */
    val status: StateFlow<String?> = _status

    val pendingCount: Int get() = queue.size()

    /** Registra il callback di rete; chiamato all'avvio del processo (anche in Direct Boot). */
    fun start() {
        if (started) return
        started = true
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            // Reti reali (non la VPN di SafeGuard): avvisano quando torna Wi-Fi o dati mobili.
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = flushSoon(NETWORK_SETTLE_MS)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) flushSoon(NETWORK_SETTLE_MS)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Callback di rete non registrato: ${e.javaClass.simpleName}")
        }
        if (queue.size() > 0) flushSoon(NETWORK_SETTLE_MS)
    }

    /** Chiamato dal Registro Allarmi (thread del registro) per ogni nuova riga. */
    fun onSecurityEvent(event: SecurityEventLog) {
        val snapshot = config.snapshot
        if (!snapshot.ready) return
        val channel = AlertPolicy.channelOf(event.type, event.title) ?: return
        if (!snapshot.prefs.allows(channel)) return
        if (channel == AlertChannel.CRITICAL) {
            queue.add(QueuedAlert(event.timestamp, AlertPolicy.formatImmediate(event.severity, event.title, event.detail, event.timestamp, zone)))
            flushSoon(0)
        } else {
            val delayMs = synchronized(aggregator) {
                // Tempo monotòno: spostare l'orologio non altera la finestra anti-429.
                aggregator.add(BlockItem(event.timestamp, event.title, event.detail), SystemClock.elapsedRealtime())
            } ?: return
            scope.launch {
                delay(delayMs)
                val message = synchronized(aggregator) { aggregator.drain(SystemClock.elapsedRealtime(), zone) }
                if (message != null) {
                    queue.add(message)
                    flush()
                }
            }
        }
    }

    fun flushSoon(delayMs: Long) {
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            flush()
        }
    }

    /** Svuota la coda; se un invio è già in corso, lo ripete al termine. */
    suspend fun flush(): FlushOutcome {
        if (!flushLock.tryLock()) {
            flushAgain = true   // l'invio in corso ripeterà il giro e consegnerà anche le nuove voci
            return FlushOutcome.Done
        }
        try {
            var outcome: FlushOutcome
            do {
                flushAgain = false
                val s = config.snapshot
                outcome = if (s.ready) dispatcher.flush(s.token, s.chatId) else FlushOutcome.NotConfigured
            } while (flushAgain && outcome is FlushOutcome.Done)
            handle(outcome)
            return outcome
        } finally {
            flushLock.unlock()
        }
    }

    private fun handle(outcome: FlushOutcome) {
        val now = AlertPolicy.dateTime(System.currentTimeMillis(), zone)
        when (outcome) {
            is FlushOutcome.Done -> _status.value = "Ultimo invio riuscito: $now"
            is FlushOutcome.NotConfigured -> Unit
            is FlushOutcome.Offline -> {
                _status.value = "Offline (${outcome.reason}): ${queue.size()} avvisi in coda, partiranno al ritorno della rete"
                scheduleRetry(OFFLINE_RETRY_MS)
                scheduleWork()
            }
            is FlushOutcome.RetryAfter -> {
                _status.value = "Telegram chiede di rallentare: nuovo invio tra ${outcome.delayMs / 1000} s"
                scheduleRetry(outcome.delayMs)
            }
            is FlushOutcome.ConfigError -> _status.value = "Errore di configurazione: ${outcome.description}"
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            flush()
        }
    }

    /** Riserva: WorkManager riprova con il vincolo "rete connessa" (solo a utente sbloccato). */
    private fun scheduleWork() {
        if (!DeviceStorage.isUnlocked(context)) return
        try {
            val request = OneTimeWorkRequestBuilder<TelegramFlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        } catch (e: Exception) {
            Log.w(TAG, "Lavoro di invio non pianificato: ${e.javaClass.simpleName}")
        }
    }

    // -------------------------------------------------------------------------------------------
    // Collegamento dalla UI
    // -------------------------------------------------------------------------------------------

    sealed class ConnectResult {
        data class Connected(val chat: TelegramChat) : ConnectResult()
        data class Error(val message: String) : ConnectResult()
    }

    /**
     * Auto-rilevamento del Chat ID: legge gli ultimi messaggi ricevuti dal bot (getUpdates),
     * prende la chat dell'ultimo /start del tutore, invia la conferma e salva il collegamento.
     */
    suspend fun fetchChatIdFromUpdates(rawToken: String): ConnectResult = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        if (!TelegramApi.isValidToken(token)) {
            return@withContext ConnectResult.Error("Il token non ha il formato corretto (es. 123456789:AAH...). Copialo di nuovo da @BotFather.")
        }
        when (val updates = transport.getUpdates(token)) {
            is UpdatesResult.NetworkError -> ConnectResult.Error("Nessuna connessione con Telegram (${updates.message}). Controlla la rete e riprova.")
            is UpdatesResult.Failed -> ConnectResult.Error("Telegram ha rifiutato la richiesta: ${updates.description}")
            is UpdatesResult.Ok -> {
                val chat = TelegramApi.parseStartChat(updates.body)
                    ?: return@withContext ConnectResult.Error(
                        "Nessun messaggio trovato. Apri la chat del nuovo bot su Telegram, premi AVVIA (/start) e riprova entro qualche minuto."
                    )
                connect(token, chat.id, chat)
            }
        }
    }

    /** Collegamento con Chat ID inserito a mano (opzione avanzata). */
    suspend fun connectManually(rawToken: String, chatId: Long): ConnectResult = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        if (!TelegramApi.isValidToken(token)) return@withContext ConnectResult.Error("Il token non ha il formato corretto.")
        connect(token, chatId, TelegramChat(chatId, "", null))
    }

    private fun connect(token: String, chatId: Long, chat: TelegramChat): ConnectResult =
        when (val sent = transport.sendMessage(token, chatId, AlertPolicy.CONNECTED_MESSAGE)) {
            is SendResult.Ok -> {
                config.saveConnection(token, chatId, chat.displayName)
                _status.value = "Collegato: ${AlertPolicy.dateTime(System.currentTimeMillis(), zone)}"
                flushSoon(0)
                ConnectResult.Connected(chat)
            }
            is SendResult.RetryAfter -> ConnectResult.Error("Telegram chiede di attendere ${sent.seconds} s prima di riprovare.")
            is SendResult.Failed -> ConnectResult.Error("Invio del messaggio di conferma fallito: ${sent.description}")
            is SendResult.NetworkError -> ConnectResult.Error("Nessuna connessione con Telegram (${sent.message}).")
        }

    /** Messaggio di prova sul collegamento salvato. */
    suspend fun sendTest(): SendResult = withContext(Dispatchers.IO) {
        val s = config.snapshot
        val token = s.token
        val chatId = s.chatId
        if (token.isNullOrBlank() || chatId == null) {
            SendResult.Failed(0, "Telegram non è ancora collegato", permanent = true)
        } else {
            transport.sendMessage(token, chatId, AlertPolicy.testMessage(System.currentTimeMillis(), zone)).also {
                if (it is SendResult.Ok) _status.value = "Messaggio di prova inviato: ${AlertPolicy.dateTime(System.currentTimeMillis(), zone)}"
            }
        }
    }

    /**
     * Report giornaliero di stato. [manual] = richiesto dal tutore dalla dashboard (parte anche
     * se il report automatico è disattivato). Il messaggio passa dalla coda: se il telefono è
     * offline parte al ritorno della rete.
     */
    suspend fun sendHeartbeat(manual: Boolean): FlushOutcome = withContext(Dispatchers.IO) {
        val s = config.snapshot
        if (!s.ready || (!manual && !s.prefs.heartbeat)) return@withContext FlushOutcome.NotConfigured
        val app = context.applicationContext as SafeGuardApp
        val now = TrustedClock.now()
        val totals = try {
            app.database.securityEventDao().totalsSince(now - 24 * 60 * 60_000L).associate { it.type to it.total }
        } catch (e: Exception) {
            emptyMap()
        }
        val stats = AlertPolicy.HeartbeatStats(
            vpnOk = !app.pinManager.protectionEnabled || DomainBlockerVpnService.running.value,
            guardOk = SettingsGuardAccessibilityService.isEnabled(app),
            domains = totals[EventType.DOMAIN] ?: 0,
            searches = totals[EventType.SEARCH] ?: 0,
            settingsAttempts = totals[EventType.TAMPER] ?: 0,
            lastListsUpdate = try { app.database.domainDao().lastSyncAt() } catch (e: Exception) { null },
        )
        PowerHistory.markAlive(app)
        queue.add(QueuedAlert(now, AlertPolicy.formatHeartbeat(stats, now, zone)))
        flush()
    }

    fun clearQueue() = queue.clear()

    companion object {
        private const val TAG = "SafeGuardTelegram"
        private const val QUEUE_FILE = "telegram_queue.txt"
        private const val WORK_NAME = "telegram-flush"
        private const val NETWORK_SETTLE_MS = 2_000L
        private const val OFFLINE_RETRY_MS = 5 * 60_000L
    }
}

/** Riserva di [TelegramNotifier]: svuota la coda quando WorkManager vede una rete connessa. */
class TelegramFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SafeGuardApp
        return when (app.telegram.flush()) {
            is FlushOutcome.Offline -> Result.retry()
            else -> Result.success()
        }
    }
}
