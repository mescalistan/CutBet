package com.zerobet.safeguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.security.DeviceStorage
import com.zerobet.safeguard.security.ShutdownRecorder

/** Spegnimento del telefono: registrazione sincrona tenuta aperta con goAsync (vedi [ShutdownRecorder]). */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DeviceStorage.isReady(context)) return
        val app = context.applicationContext as? SafeGuardApp ?: return
        ShutdownRecorder.onShutdown(app, goAsync())
    }

    companion object {
        const val ACTION_QUICKBOOT_POWEROFF = "android.intent.action.QUICKBOOT_POWEROFF"
    }
}
