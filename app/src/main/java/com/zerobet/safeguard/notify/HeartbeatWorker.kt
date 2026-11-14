package com.zerobet.safeguard.notify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zerobet.safeguard.SafeGuardApp
import java.util.concurrent.TimeUnit

/**
 * "Battito cardiaco" giornaliero su Telegram. Se il tutore non lo riceve per più di 24 ore, il
 * telefono è spento, in Modalità Provvisoria (dove nessuna app di terze parti gira) o senza rete.
 * Nessun vincolo di rete: il messaggio va in coda all'ora giusta e parte appena c'è connessione.
 */
class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SafeGuardApp
        app.telegram.sendHeartbeat(manual = false)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "telegram-heartbeat"

        fun schedule(context: Context) {
            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<HeartbeatWorker>(24, TimeUnit.HOURS).build()
                )
            } catch (e: Exception) {
                Log.w("SafeGuardHeartbeat", "Report giornaliero non pianificato: ${e.javaClass.simpleName}")
            }
        }
    }
}
