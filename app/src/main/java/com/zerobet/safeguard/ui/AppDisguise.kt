package com.zerobet.safeguard.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Modalità Discreta: nome e icona dell'app nella Home. Tre `<activity-alias>` del manifest puntano
 * alla stessa MainActivity; ne è abilitato uno solo alla volta (DONT_KILL_APP: la protezione
 * continua a girare). Chi vede il telefono non trova un'app "anti-scommesse" in bella vista.
 */
object AppDisguise {

    enum class Appearance(val alias: String, val homeLabel: String, val title: String) {
        DEFAULT(".LauncherDefault", "SafeGuard", "Predefinito: \"SafeGuard\" (icona scudo)"),
        NETWORK(".LauncherNetwork", "Servizi di Rete", "Discreto - Sistema: \"Servizi di Rete\" (icona ingranaggio)"),
        BATTERY(".LauncherBattery", "Gestione Batteria", "Discreto - Utility: \"Gestione Batteria\" (icona batteria)"),
    }

    private const val NAMESPACE = "com.zerobet.safeguard"

    private fun component(context: Context, appearance: Appearance) =
        ComponentName(context.packageName, NAMESPACE + appearance.alias)

    fun current(context: Context): Appearance {
        val pm = context.packageManager
        return Appearance.entries.firstOrNull { appearance ->
            when (pm.getComponentEnabledSetting(component(context, appearance))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> appearance == Appearance.DEFAULT
                else -> false
            }
        } ?: Appearance.DEFAULT
    }

    /** Abilita prima l'alias scelto e poi disabilita gli altri: in Home c'è sempre un'icona. */
    fun apply(context: Context, choice: Appearance) {
        val pm = context.packageManager
        pm.setComponentEnabledSetting(
            component(context, choice), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        Appearance.entries.filter { it != choice }.forEach {
            pm.setComponentEnabledSetting(
                component(context, it), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
        }
    }
}

/** Selettore "Aspetto App nella Home (Modalità Discreta)" della dashboard (dietro PIN). */
@Composable
fun AppearanceCard() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppDisguise.current(context)) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Aspetto App nella Home (Modalità Discreta)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Cambia nome e icona con cui l'app compare nella schermata Home, per evitare domande imbarazzanti. " +
                    "La protezione resta identica; il launcher può impiegare qualche secondo ad aggiornarsi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppDisguise.Appearance.entries.forEach { appearance ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        AppDisguise.apply(context, appearance)
                        selected = appearance
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected == appearance, onClick = {
                        AppDisguise.apply(context, appearance)
                        selected = appearance
                    })
                    Text(appearance.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
