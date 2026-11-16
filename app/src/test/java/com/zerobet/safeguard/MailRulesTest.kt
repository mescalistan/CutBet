package com.zerobet.safeguard

import com.zerobet.safeguard.guard.Box
import com.zerobet.safeguard.guard.ContentClassifier
import com.zerobet.safeguard.guard.GuardDecision
import com.zerobet.safeguard.guard.InAppContentAndAdFilter
import com.zerobet.safeguard.guard.ItemRole
import com.zerobet.safeguard.guard.MailApps
import com.zerobet.safeguard.guard.MailRules
import com.zerobet.safeguard.guard.ScreenDecider
import com.zerobet.safeguard.guard.ScreenItem
import com.zerobet.safeguard.guard.ScreenModel
import com.zerobet.safeguard.guard.SearchQueryClassifier
import com.zerobet.safeguard.guard.TelegramRules
import com.zerobet.safeguard.guard.TextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailRulesTest {

    private val screen = Box(0, 0, 1080, 2400)
    private var y = 400

    private fun row(vararg texts: String, height: Int = 200): ScreenItem {
        val item = ScreenItem(texts.toList(), Box(0, y, 1080, y + height), ItemRole.CONTENT)
        y += height + 8
        return item
    }

    private fun header(vararg texts: String) = ScreenItem(texts.toList(), Box(0, 60, 1080, 300), ItemRole.HEADER)

    // ---------------------------------------------------------------------------------------
    // Indirizzi email e handle
    // ---------------------------------------------------------------------------------------

    @Test
    fun `gli indirizzi email non sono mai parole a rischio`() {
        val addresses = listOf("tradingonmattia@inwind.it", "betta@libero.it", "prestito.mario@gmail.com", "casinò.info@pec.it")
        for (a in addresses) {
            assertEquals(a, "", TextSanitizer.stripIdentifiers(a).trim())
            assertNull(a, SearchQueryClassifier.classify(a))
            assertNull(a, InAppContentAndAdFilter.classifyText("Account: $a"))
            assertNull(a, InAppContentAndAdFilter.findAd(listOf("Annuncio", a)))
            assertNull(a, ScreenDecider.headerHit(a))
        }
        assertNull(ContentClassifier.classify("Da: casinò.info@pec.it A: tradingonmattia@inwind.it Oggetto: verbale assemblea"))
        // La parola vera, fuori dall'indirizzo, conta ancora.
        assertNotNull(SearchQueryClassifier.classify("trading online"))
        assertNotNull(InAppContentAndAdFilter.classifyText("Guadagna 500€ al giorno col Trading Online e Forex - scrivi a info@x.it"))
        // Handle utente neutralizzati, il testo intorno resta.
        assertEquals("ciao   come va", TextSanitizer.stripIdentifiers("ciao @trader_luca come va").replace("  ", "  "))
    }

    @Test
    fun `gli inviti di Telegram restano riconosciuti`() {
        assertTrue(TelegramRules.isRiskyHandle("CasinoBonusBot"))
        assertNotNull(TelegramRules.findRiskyLink(listOf("Entra qui: @CasinoBonusBot")) { false })
    }

    // ---------------------------------------------------------------------------------------
    // Elenco della posta in arrivo: oscura le righe di spam, non chiude mai
    // ---------------------------------------------------------------------------------------

    private fun inbox(): Triple<ScreenModel, ScreenItem, ScreenItem> {
        y = 400
        val enel = row("Enel Energia", "Bolletta Enel Energia - Scadenza fattura", "Gentile cliente, la fattura di ottobre…")
        val spam1 = row("Forex Academy", "Guadagna 500€ al giorno col Trading Online e Forex - Apri conto", "Inizia oggi a guadagnare…")
        val amazon = row("Amazon.it", "Conferma ordine Amazon - Consegna domani", "Il tuo ordine è stato spedito")
        val spam2 = row("BetClub", "Bonus 100€ Free Spin sul Casinò e Scommesse Serie A", "Registrati ora")
        val condo = row("Amministratore", "Riunione di condominio - Verbale assemblea", "In allegato il verbale")
        val model = ScreenModel(
            listOf(header("tradingonmattia@inwind.it", "Cerca nella posta", "Posta in arrivo"), enel, spam1, amazon, spam2, condo,
                ScreenItem(listOf("Scrivi"), Box(700, 2150, 1040, 2300), ItemRole.CONTENT)),
            screen,
        )
        return Triple(model, spam1, spam2)
    }

    @Test
    fun `posta in arrivo con spam di trading e casino oscura solo quelle righe`() {
        val (model, spam1, spam2) = inbox()
        assertEquals(MailRules.View.INBOX, MailRules.viewOf(model))
        val d = MailRules.decide(model)
        assertTrue(d.toString(), d is GuardDecision.MaskRects)
        assertEquals(setOf(spam1.box, spam2.box), (d as GuardDecision.MaskRects).boxes.toSet())
    }

    @Test
    fun `casella piena solo di spam non viene mai chiusa`() {
        y = 400
        val rows = (1..6).map { row("Promo $it", "Trading online e forex: guadagna subito con i segnali", "Apri conto") }
        val model = ScreenModel(listOf(header("Posta in arrivo")) + rows + row("Scrivi"), screen)
        val d = MailRules.decide(model)
        assertFalse(d.toString(), d is GuardDecision.CloseScreen)
        assertTrue(d is GuardDecision.MaskRects)
    }

    // ---------------------------------------------------------------------------------------
    // Email aperta: Indietro se a rischio, libera se innocua
    // ---------------------------------------------------------------------------------------

    @Test
    fun `email di trading aperta viene chiusa con Indietro`() {
        y = 400
        val model = ScreenModel(
            listOf(
                header("Guadagna 500€ al giorno col Trading Online e Forex - Apri conto"),
                row("Da: Forex Academy <promo@forexacademy.biz>", "A: tradingonmattia@inwind.it"),
                row("Con il nostro corso di trading online impari a fare trading sul forex con la leva."),
                row("Rispondi", "Inoltra", height = 100),
            ),
            screen,
        )
        assertEquals(MailRules.View.DETAIL, MailRules.viewOf(model))
        assertTrue(MailRules.decide(model) is GuardDecision.CloseScreen)
    }

    @Test
    fun `bolletta inviata a tradingonmattia resta aperta senza pannelli`() {
        y = 400
        val model = ScreenModel(
            listOf(
                header("Bolletta Enel Energia - Scadenza fattura"),
                row("Da: Enel Energia <noreply@enel.it>", "A: tradingonmattia@inwind.it"),
                row("Gentile cliente, la fattura di ottobre di 84,20 € scade il 30 novembre. Puoi pagarla con domiciliazione."),
                row("Rispondi", "Inoltra", height = 100),
            ),
            screen,
        )
        assertEquals(MailRules.View.DETAIL, MailRules.viewOf(model))
        assertEquals(GuardDecision.None, MailRules.decide(model))
    }

    @Test
    fun `client di posta e webmail riconosciuti`() {
        listOf("it.italiaonline.mail", "it.virgilio.mail", "com.google.android.gm", "com.microsoft.office.outlook",
            "com.yahoo.mobile.client.android.mail", "com.samsung.android.email.provider", "ch.protonmail.android",
            "de.gmx.mobile.android.mail").forEach { assertTrue(it, MailApps.isMail(it, null)) }
        assertTrue(MailApps.isMail("com.android.chrome", "mail.libero.it"))
        assertTrue(MailApps.isMail("com.android.chrome", "mail.google.com"))
        assertFalse(MailApps.isMail("com.android.chrome", "www.google.com"))
    }
}
