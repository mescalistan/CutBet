package com.zerobet.safeguard.notify

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.zerobet.safeguard.security.DeviceStorage

/**
 * Credenziali e preferenze degli avvisi Telegram.
 *
 * EncryptedSharedPreferences (AES-256, chiave nell'Android Keystore) nello storage protetto dal
 * dispositivo: token e chat sono leggibili anche subito dopo un riavvio, a schermo ancora
 * bloccato, così gli avvisi di LOCKED_BOOT_COMPLETED partono senza attendere lo sblocco.
 * I valori sono tenuti anche in memoria: il notifier li legge a ogni evento del registro.
 */
class TelegramConfig(context: Context) {

    private val prefs: SharedPreferences = run {
        val device = DeviceStorage.context(context)
        val masterKey = MasterKey.Builder(device).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            device,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    data class Snapshot(
        val token: String?,
        val chatId: Long?,
        val chatName: String?,
        val enabled: Boolean,
        val prefs: AlertPrefs,
    ) {
        /** Pronto a inviare: attivo, con token e chat. */
        val ready: Boolean get() = enabled && !token.isNullOrBlank() && chatId != null
    }

    @Volatile var snapshot: Snapshot = read()
        private set

    private fun read() = Snapshot(
        token = prefs.getString(KEY_TOKEN, null),
        chatId = if (prefs.contains(KEY_CHAT_ID)) prefs.getLong(KEY_CHAT_ID, 0L) else null,
        chatName = prefs.getString(KEY_CHAT_NAME, null),
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        prefs = AlertPrefs(
            critical = prefs.getBoolean(KEY_CRITICAL, true),
            blocks = prefs.getBoolean(KEY_BLOCKS, true),
            domains = prefs.getBoolean(KEY_DOMAINS, false),
        ),
    )

    /** Salva token e chat collegati e attiva gli avvisi. */
    fun saveConnection(token: String, chatId: Long, chatName: String?) {
        prefs.edit()
            .putString(KEY_TOKEN, token.trim())
            .putLong(KEY_CHAT_ID, chatId)
            .putString(KEY_CHAT_NAME, chatName)
            .putBoolean(KEY_ENABLED, true)
            .commit()
        snapshot = read()
    }

    fun setEnabled(enabled: Boolean) = update { putBoolean(KEY_ENABLED, enabled) }

    fun setPrefs(value: AlertPrefs) = update {
        putBoolean(KEY_CRITICAL, value.critical)
        putBoolean(KEY_BLOCKS, value.blocks)
        putBoolean(KEY_DOMAINS, value.domains)
    }

    /** Rimuove il collegamento (token e chat); le preferenze restano. */
    fun disconnect() = update {
        remove(KEY_TOKEN)
        remove(KEY_CHAT_ID)
        remove(KEY_CHAT_NAME)
        putBoolean(KEY_ENABLED, false)
    }

    private fun update(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).commit()
        snapshot = read()
    }

    private companion object {
        const val FILE_NAME = "safeguard_telegram_prefs"
        const val KEY_TOKEN = "bot_token"
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_CHAT_NAME = "chat_name"
        const val KEY_ENABLED = "enabled"
        const val KEY_CRITICAL = "notify_critical"
        const val KEY_BLOCKS = "notify_blocks"
        const val KEY_DOMAINS = "notify_domains"
    }
}
