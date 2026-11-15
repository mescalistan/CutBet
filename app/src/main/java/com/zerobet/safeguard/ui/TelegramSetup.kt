package com.zerobet.safeguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.notify.SendResult
import com.zerobet.safeguard.notify.TelegramApi
import com.zerobet.safeguard.notify.TelegramNotifier
import com.zerobet.safeguard.ui.theme.SafeGreen
import kotlinx.coroutines.launch

/**
 * Configurazione degli avvisi Telegram per il tutore: guida in 3 passi, token del bot,
 * collegamento automatico (getUpdates) o Chat ID manuale, livelli di dettaglio e prova.
 * Usata nell'onboarding e nella scheda "Notifiche Telegram" della dashboard (dietro PIN).
 */
@Composable
fun TelegramSetupSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val notifier = remember { (context.applicationContext as SafeGuardApp).telegram }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val status by notifier.status.collectAsStateWithLifecycle()

    var snapshot by remember { mutableStateOf(notifier.config.snapshot) }
    var token by rememberSaveable { mutableStateOf(snapshot.token.orEmpty()) }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var manualChat by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }   // (ok, testo)

    fun refresh() {
        snapshot = notifier.config.snapshot
    }

    fun connect(action: suspend () -> TelegramNotifier.ConnectResult) {
        busy = true
        result = null
        scope.launch {
            result = when (val r = action()) {
                is TelegramNotifier.ConnectResult.Connected ->
                    true to "Collegato a: ${r.chat.displayName} (ID: ${r.chat.id}). Messaggio di conferma inviato."
                is TelegramNotifier.ConnectResult.Error -> false to r.message
            }
            busy = false
            refresh()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuideCard()

        // ---- Token del bot ----
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim(); result = null },
            label = { Text("Token HTTP API del bot") },
            placeholder = { Text("123456789:AAH...") },
            singleLine = true,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = token.isNotEmpty() && !TelegramApi.isValidToken(token),
            supportingText = {
                if (token.isNotEmpty() && !TelegramApi.isValidToken(token)) Text("Formato non valido: copia il token completo da @BotFather")
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.getText()?.text?.trim()?.let { token = it; result = null }
            }) { Text("Incolla") }
            OutlinedButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Nascondi" else "Mostra") }
        }

        Button(
            onClick = { connect { notifier.fetchChatIdFromUpdates(token) } },
            enabled = !busy && TelegramApi.isValidToken(token),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("Collega Automaticamente e Invia Test")
        }

        result?.let { (ok, text) ->
            Text(
                text,
                color = if (ok) SafeGreen else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (ok) FontWeight.SemiBold else FontWeight.Normal
            )
        }

        // ---- Chat ID manuale (avanzato) ----
        TextButton(onClick = { manualOpen = !manualOpen }) {
            Text(if (manualOpen) "▾ Inserisci Chat ID manualmente (avanzato)" else "▸ Inserisci Chat ID manualmente (avanzato)")
        }
        if (manualOpen) {
            OutlinedTextField(
                value = manualChat,
                onValueChange = { manualChat = it.filter { c -> c.isDigit() || c == '-' } },
                label = { Text("Chat ID") },
                placeholder = { Text("es. 123456789") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                onClick = {
                    val id = TelegramApi.parseChatId(manualChat) ?: return@OutlinedButton
                    connect { notifier.connectManually(token, id) }
                },
                enabled = !busy && TelegramApi.isValidToken(token) && TelegramApi.parseChatId(manualChat) != null
            ) { Text("Salva e invia test") }
        }

        // ---- Stato del collegamento e preferenze ----
        if (snapshot.chatId != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Collegato a: ${snapshot.chatName?.takeIf { it.isNotBlank() } ?: "chat"} (ID: ${snapshot.chatId})",
                        fontWeight = FontWeight.SemiBold
                    )
                    SwitchRow("Avvisi Telegram attivi", snapshot.enabled) {
                        notifier.config.setEnabled(it); refresh()
                        if (it) notifier.flushSoon(0)
                    }
                    Text("Cosa inviare su Telegram", style = MaterialTheme.typography.labelLarge)
                    val p = snapshot.prefs
                    SwitchRow("Avvisi Critici (SIM, Riavvii, PIN errati, Tentativi di sblocco Impostazioni)", p.critical) {
                        notifier.config.setPrefs(p.copy(critical = it)); refresh()
                    }
                    SwitchRow("Ricerche bloccate e App vietate aperte", p.blocks) {
                        notifier.config.setPrefs(p.copy(blocks = it)); refresh()
                    }
                    SwitchRow("Domini e pubblicità bloccati (più messaggi)", p.domains) {
                        notifier.config.setPrefs(p.copy(domains = it)); refresh()
                    }
                    SwitchRow("Report giornaliero di stato (Heartbeat)", p.heartbeat) {
                        notifier.config.setPrefs(p.copy(heartbeat = it)); refresh()
                    }
                    Text(
                        "Ogni 24 ore ricevi \"💓 CutBet Attivo\" con lo stato della protezione e i blocchi del giorno. " +
                            "Se per più di 24 ore non ricevi il messaggio giornaliero 💓, il telefono potrebbe essere spento, " +
                            "in Modalità Provvisoria o disconnesso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Gli avvisi critici partono subito; i blocchi sono raggruppati in al massimo un messaggio al minuto. " +
                            "Se il telefono è offline gli avvisi restano in coda e partono in ordine appena torna la rete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    result = when (val r = notifier.sendTest()) {
                                        is SendResult.Ok -> true to "Messaggio di prova inviato."
                                        is SendResult.Failed -> false to "Invio fallito: ${r.description}"
                                        is SendResult.RetryAfter -> false to "Telegram chiede di attendere ${r.seconds} s."
                                        is SendResult.NetworkError -> false to "Nessuna connessione con Telegram (${r.message})."
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy
                        ) { Text("Invia messaggio di prova") }
                        OutlinedButton(
                            onClick = {
                                busy = true
                                scope.launch {
                                    result = when (val r = notifier.sendHeartbeat(manual = true)) {
                                        is com.zerobet.safeguard.notify.FlushOutcome.Done -> true to "Report di stato inviato."
                                        is com.zerobet.safeguard.notify.FlushOutcome.Offline -> false to "Offline: il report partirà al ritorno della rete."
                                        is com.zerobet.safeguard.notify.FlushOutcome.RetryAfter -> false to "Telegram chiede di attendere: il report è in coda."
                                        is com.zerobet.safeguard.notify.FlushOutcome.ConfigError -> false to "Errore: ${r.description}"
                                        is com.zerobet.safeguard.notify.FlushOutcome.NotConfigured -> false to "Telegram non è collegato."
                                    }
                                    busy = false
                                }
                            },
                            enabled = !busy
                        ) { Text("Report ora") }
                        TextButton(onClick = {
                            notifier.config.disconnect(); notifier.clearQueue(); refresh()
                            token = ""; result = null
                        }) { Text("Scollega") }
                    }
                    val pending = notifier.pendingCount
                    if (pending > 0) Text("Avvisi in coda: $pending", style = MaterialTheme.typography.bodySmall)
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Guida in 3 passi per creare il bot e collegarlo (circa 60 secondi). */
@Composable
private fun GuideCard() {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val mono = SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Avvisi sul telefono del tutore in 60 secondi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(buildAnnotatedString {
                withStyle(bold) { append("1. ") }
                append("Sul TUO telefono (quello del tutore), apri Telegram, cerca ")
                withStyle(bold) { append("@BotFather") }
                append(" (col badge blu verificato), premi Avvia e invia il comando ")
                withStyle(mono) { append("/newbot") }
                append(".")
            })
            Text(buildAnnotatedString {
                withStyle(bold) { append("2. ") }
                append("Scegli un nome qualsiasi e un username che finisca per ")
                withStyle(mono) { append("bot") }
                append(" (es. ")
                withStyle(mono) { append("MioCutBet_bot") }
                append("), poi copia il ")
                withStyle(bold) { append("Token HTTP API") }
                append(" che @BotFather ti fornisce e incollalo nel campo qui sotto.")
            })
            Text(buildAnnotatedString {
                withStyle(bold) { append("3. ") }
                append("Apri la chat del nuovo bot che hai appena creato su Telegram, premi ")
                withStyle(bold) { append("AVVIA") }
                append(" (")
                withStyle(mono) { append("/start") }
                append(") e poi tocca il pulsante ")
                withStyle(bold) { append("Collega Automaticamente e Invia Test") }
                append(" qui sotto.")
            })
            Text(
                "Ogni giorno riceverai anche un messaggio 💓 di stato. Se per più di 24 ore non ricevi il messaggio " +
                    "giornaliero 💓, il telefono potrebbe essere spento, in Modalità Provvisoria o disconnesso.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Il token resta cifrato su questo telefono e viene usato solo per inviare gli avvisi al tuo bot.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
