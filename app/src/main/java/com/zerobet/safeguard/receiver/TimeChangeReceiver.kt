package com.zerobet.safeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zerobet.safeguard.security.DeviceStorage
import com.zerobet.safeguard.security.TrustedClock

/**
 * Cambio di data/ora o di fuso orario (broadcast di sistema consegnati anche ai receiver del
 * manifest). Lo spostamento dell'orologio viene confrontato con il tempo monotòno: se è una
 * manomissione finisce nel Registro Allarmi come evento critico (e quindi su Telegram).
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DeviceStorage.isReady(context)) return
        when (intent.action) {
            Intent.ACTION_TIME_CHANGED -> TrustedClock.onTimeChanged(context)
            Intent.ACTION_TIMEZONE_CHANGED -> TrustedClock.onTimeZoneChanged(context, intent.getStringExtra("time-zone"))
        }
    }
}
