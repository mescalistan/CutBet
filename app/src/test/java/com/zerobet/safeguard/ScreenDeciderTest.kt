package com.zerobet.safeguard

import com.zerobet.safeguard.guard.Box
import com.zerobet.safeguard.guard.GuardDecision
import com.zerobet.safeguard.guard.ItemRole
import com.zerobet.safeguard.guard.Marketplaces
import com.zerobet.safeguard.guard.ScreenDecider
import com.zerobet.safeguard.guard.ScreenItem
import com.zerobet.safeguard.guard.ScreenModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenDeciderTest {

    private val screen = Box(0, 0, 1080, 2400)
    private var y = 300

    /** Box impilati come in un feed: ogni chiamata occupa la riga successiva. */
    private fun content(vararg texts: String, height: Int = 220): ScreenItem {
        val item = ScreenItem(texts.toList(), Box(0, y, 1080, y + height), ItemRole.CONTENT)
        y += height + 10
        return item
    }

    private fun header(vararg texts: String) = ScreenItem(texts.toList(), Box(0, 60, 1080, 220), ItemRole.HEADER)

    private fun decide(vararg items: ScreenItem, marketplace: Boolean = false) =
        ScreenDecider.decide(ScreenModel(items.toList(), screen, marketplace))

    // ---------------------------------------------------------------------------------------
    // Caso A: contesto principale vietato -> CLOSE_SCREEN
    // ---------------------------------------------------------------------------------------

    @Test
    fun `gruppo Facebook di investimenti e trading chiude la schermata`() {
        val d = decide(
            header("Facebook"),
            content("Investimenti e Trading Italia", "Gruppo pubblico · 12.300 membri", "Unisciti al gruppo"),
            content("Mario Rossi", "Buongiorno a tutti, oggi parliamo di come gestire il portafoglio"),
        )
        assertTrue(d.toString(), d is GuardDecision.CloseScreen)

        val d2 = decide(content("Gruppo pubblico · Trading e Investimenti Sicuri", "8.500 membri"))
        assertTrue(d2.toString(), d2 is GuardDecision.CloseScreen)

        // Pagina web: titolo e "Gruppo pubblico · membri" in contenitori separati.
        val d3 = decide(
            content("Trading e Investimenti Sicuri", height = 120),
            content("Gruppo pubblico · 12.300 membri", height = 60),
            content("Unisciti al gruppo", height = 60),
            content("Luca B. Oggi ho aperto una posizione long su EUR/USD, chi mi segue?"),
        )
        assertTrue(d3.toString(), d3 is GuardDecision.CloseScreen)
        // Un gruppo innocuo resta aperto.
        assertEquals(
            GuardDecision.None,
            decide(content("Amici della montagna", height = 120), content("Gruppo pubblico · 3.200 membri", height = 60), content("Gita al lago domenica"))
        )
    }

    @Test
    fun `titolo della pagina a rischio chiude la schermata`() {
        assertTrue(decide(header("Investimenti"), content("Una notizia qualsiasi del giorno")) is GuardDecision.CloseScreen)
        assertTrue(decide(header("Scommesse sportive"), content("Inter - Milan")) is GuardDecision.CloseScreen)
    }

    @Test
    fun `contenuto principale a schermo intero a rischio chiude`() {
        val article = ScreenItem(
            listOf("Trading online per principianti: la guida per guadagnare con il forex"),
            Box(0, 200, 1080, 2300), ItemRole.CONTENT
        )
        assertTrue(decide(article) is GuardDecision.CloseScreen)
    }

    @Test
    fun `pagina di video tutti sul trading chiude`() {
        val d = decide(
            content("Corso di trading per principianti - lezione 1"),
            content("Le migliori strategie di trading del 2026"),
            content("Segnali forex gratis ogni giorno"),
            content("Ricetta della torta di mele"),
        )
        assertTrue(d.toString(), d is GuardDecision.CloseScreen)
    }

    // ---------------------------------------------------------------------------------------
    // Caso B: banner o box isolato in un contesto innocuo -> MASK_RECTS
    // ---------------------------------------------------------------------------------------

    @Test
    fun `annuncio di Subito con box sponsorizzato e calcola rata viene oscurato, app aperta`() {
        val sponsor = content("Sponsorizzato", "Investi con eToro", "Scopri di più")
        val rata = content("Calcola rata finanziamento", "TAN 6,90% TAEG 8,12%")
        val d = decide(
            header("Subito", "Fiat Panda 1.2 Easy 2019"),
            content("Fiat Panda 1.2 Easy 2019", "8.900 €", "Torino (TO)"),
            content("Descrizione", "Unico proprietario, tagliandi regolari, gomme nuove"),
            sponsor, rata,
            marketplace = true,
        )
        assertTrue(d.toString(), d is GuardDecision.MaskRects)
        d as GuardDecision.MaskRects
        assertEquals(setOf(sponsor.box, rata.box), d.boxes.toSet())
    }

    @Test
    fun `articolo di giornale con un banner di trading a meta pagina oscura solo il banner`() {
        val ad = content("Sponsorizzato", "Trading Online", "Investi nel Forex con leva 1:500", "Iscriviti")
        val d = decide(
            header("corriere.it"),
            content("Previsioni meteo del fine settimana"),
            content("Sabato cielo sereno al nord, qualche nuvola al centro."),
            ad,
            content("Lunedì possibili piogge sulle regioni tirreniche."),
        )
        assertTrue(d.toString(), d is GuardDecision.MaskRects)
        assertEquals(listOf(ad.box), (d as GuardDecision.MaskRects).boxes)
    }

    @Test
    fun `un solo post a rischio in un feed generico viene oscurato`() {
        val post = content("Ho iniziato a fare trading online e ho triplicato i soldi in un mese")
        val d = decide(
            content("Foto delle vacanze in montagna con la famiglia"),
            content("Buon compleanno nonna! Cento di questi giorni"),
            post,
            content("La ricetta della carbonara perfetta"),
            content("Risultati della partita di ieri sera"),
            content("Nuovo parco giochi inaugurato in centro"),
        )
        assertTrue(d.toString(), d is GuardDecision.MaskRects)
    }

    @Test
    fun `etichetta e marchio in contenitori vicini vengono oscurati insieme`() {
        val label = content("Sponsorizzato", height = 60)
        val brand = content("Apri un conto su eToro e investi in azioni", height = 200)
        val d = decide(content("Notizie dal mondo dello sport e della cultura"), label, brand)
        assertTrue(d.toString(), d is GuardDecision.MaskRects)
        val box = (d as GuardDecision.MaskRects).boxes.single()
        assertTrue(box.top <= label.box.top && box.bottom >= brand.box.bottom)
    }

    // ---------------------------------------------------------------------------------------
    // Marketplace: mai uscire per i contenuti
    // ---------------------------------------------------------------------------------------

    @Test
    fun `nei marketplace si esce solo dal modulo di richiesta del finanziamento`() {
        val form = decide(header("Richiedi finanziamento"), content("Importo", "Durata in mesi"), marketplace = true)
        assertTrue(form.toString(), form is GuardDecision.CloseScreen)
        // Prodotti con parole ambigue: nessuna azione.
        assertEquals(GuardDecision.None, decide(header("Action cam 4K"), content("Action cam 4K impermeabile", "49 €"), marketplace = true))
        // Molti box finanziamento in una scheda: oscurati, mai chiusura.
        val many = decide(
            content("Calcola rata finanziamento"), content("Paga a rate con Agos: calcola la rata"),
            content("Richiedi un prestito per la tua auto"), marketplace = true,
        )
        assertFalse(many.toString(), many is GuardDecision.CloseScreen)
    }

    @Test
    fun `schermata innocua nessuna azione`() {
        assertEquals(
            GuardDecision.None,
            decide(header("Il Meteo"), content("Previsioni del fine settimana"), content("Temperature in aumento al sud"))
        )
    }

    @Test
    fun `riconoscimento dei marketplace`() {
        assertTrue(Marketplaces.isMarketplace("it.subito", null))
        assertTrue(Marketplaces.isMarketplace("com.android.chrome", "www.subito.it"))
        assertTrue(Marketplaces.isMarketplace("com.android.chrome", "m.ebay.it"))
        assertFalse(Marketplaces.isMarketplace("com.android.chrome", "notsubito.it"))
        assertFalse(Marketplaces.isMarketplace("com.facebook.katana", null))
    }
}
