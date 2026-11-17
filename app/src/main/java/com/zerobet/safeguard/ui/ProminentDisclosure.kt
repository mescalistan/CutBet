package com.zerobet.safeguard.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize

/**
 * Informativa in primo piano (Prominent Disclosure) richiesta dalle norme di Google Play per
 * AccessibilityService e VpnService: viene mostrata, con "Accetto e Continua" / "Annulla", PRIMA di
 * aprire la schermata di sistema che concede il permesso. Senza il consenso non si apre nulla.
 */
object Disclosures {

    enum class Kind(val title: String, val text: String) {
        ACCESSIBILITY(
            "Informativa sull'uso di AccessibilityService",
            "Informativa sull'uso di AccessibilityService: CutBet utilizza l'API AccessibilityService esclusivamente " +
                "per aiutare l'utente nell'autotutela dal gioco d'azzardo e dal trading compulsivo, rilevando sullo " +
                "schermo testi, ricerche, annunci pubblicitari o impostazioni a rischio per oscurarli con un pannello " +
                "o chiudere la schermata. Nessun testo dello schermo o dato personale viene raccolto su server esterni " +
                "o condiviso con terze parti; eventuali avvisi (con un breve estratto del contenuto bloccato) vengono " +
                "inviati solo al Bot Telegram privato configurato dal tutore."
        ),
        VPN(
            "Informativa sull'uso di VpnService",
            "Informativa sull'uso di VpnService: CutBet utilizza una VPN esclusivamente locale sul dispositivo " +
                "(DNS sinkhole) per bloccare i domini di gioco d'azzardo, trading e prestiti. Il tuo traffico internet " +
                "non viene instradato, registrato né decifrato su alcun server esterno. Le sole richieste DNS dei siti " +
                "consentiti vengono inoltrate a un resolver DNS pubblico (Cloudflare 1.1.1.1 o Google 8.8.8.8), come " +
                "farebbe normalmente il telefono."
        ),
    }

    const val ACCEPT = "Accetto e Continua"
    const val CANCEL = "Annulla"
}

/** Dialogo di consenso: [onAccept] apre la schermata di sistema, [onDismiss] chiude senza fare nulla. */
@Composable
fun DisclosureDialog(kind: Disclosures.Kind, onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(kind.title, fontWeight = FontWeight.SemiBold) },
        text = { Text(kind.text, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { Button(onClick = onAccept) { Text(Disclosures.ACCEPT) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Disclosures.CANCEL) } },
    )
}

/**
 * Fascia blu del marchio dietro barra di stato e barra di navigazione: con il disegno edge-to-edge di
 * Android 15 il colore della barra impostato nel tema viene ignorato.
 */
@Composable
fun SystemBarsBackdrop(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0D3B66))
    ) { content() }
}
