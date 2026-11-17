package com.zerobet.safeguard

import com.zerobet.safeguard.guard.Box
import com.zerobet.safeguard.guard.GuardDecision
import com.zerobet.safeguard.guard.ItemRole
import com.zerobet.safeguard.guard.OmniboxRules
import com.zerobet.safeguard.guard.ScreenItem
import com.zerobet.safeguard.ui.AppDisguise
import com.zerobet.safeguard.ui.Disclosures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OmniboxAndPlayTest {

    private val screen = Box(0, 0, 1080, 2400)
    private val bar = Box(0, 120, 1080, 300)

    /** Barra in alto e righe dei suggerimenti/cronologia sotto, come in Chrome con la tastiera aperta. */
    private fun omnibox(barText: String, vararg rows: String): List<ScreenItem> =
        listOf(ScreenItem(listOf(barText), Box(0, 120, 1080, 300), ItemRole.HEADER)) +
            rows.mapIndexed { i, r -> ScreenItem(listOf(r), Box(0, 320 + i * 140, 1080, 450 + i * 140), ItemRole.CONTENT) }

    private val history = arrayOf("trading online eToro", "ricetta pizza", "snai scommesse", "orari treni")

    @Test
    fun `cronologia vietata sotto una barra vuota o innocua viene oscurata riga per riga, mai chiusa`() {
        for (query in listOf("", "meteo domani")) {
            val decision = OmniboxRules.decide(query, omnibox(query, *history), screen, bar)
            assertTrue("query '$query': $decision", decision is GuardDecision.MaskRects)
            decision as GuardDecision.MaskRects
            // Solo le due righe vietate: la 1a (trading) e la 3a (snai); barra e righe innocue restano libere.
            assertEquals(listOf(Box(0, 320, 1080, 450), Box(0, 600, 1080, 730)), decision.boxes)
        }
    }

    @Test
    fun `ricerca vietata scritta nella barra chiude`() {
        val decision = OmniboxRules.decide("trading online", omnibox("trading online", *history), screen, bar)
        assertTrue(decision is GuardDecision.CloseScreen)
        // Mentre si scrive l'ultima parola incompleta non conta: "invest" potrebbe essere "investigatore".
        assertFalse(OmniboxRules.decide("invest", omnibox("invest"), screen) is GuardDecision.CloseScreen)
    }

    @Test
    fun `stato omnibox, testo scritto e completamento automatico`() {
        assertTrue(OmniboxRules.isOmniboxState(barFocused = true, barText = "meteo", barShowsHint = false))
        assertTrue(OmniboxRules.isOmniboxState(false, "Cerca con Google o digita un URL", barShowsHint = true))
        assertTrue(OmniboxRules.isOmniboxState(false, "", false))                 // Nuova scheda
        assertTrue(OmniboxRules.isOmniboxState(false, "chrome://history", false))  // cronologia
        assertFalse(OmniboxRules.isOmniboxState(false, "repubblica.it", false))
        // "inv" scritto, "estimenti sicuri" proposto dal browser (selezionato): conta solo "inv".
        assertEquals("inv", OmniboxRules.typedQuery("investimenti sicuri", 3, 19, showsHint = false))
        // Chrome (misurato sull'emulatore): "tr" scritto, barra "trading online", cursore a 2 senza selezione.
        assertEquals("tr", OmniboxRules.typedQuery("trading online", 2, 2, showsHint = false))
        val tr = OmniboxRules.decide("tr", omnibox("trading online", "trading online", "treni"), screen, bar)
        // Non si chiude, si oscura la sola riga di cronologia "trading online"; la barra resta libera.
        assertEquals(listOf(Box(0, 320, 1080, 450)), (tr as GuardDecision.MaskRects).boxes)
        assertEquals("trading online", OmniboxRules.typedQuery("trading online", 14, 14, showsHint = false))
        assertEquals("ciao", OmniboxRules.typedQuery("ciao", -1, -1, showsHint = false))
        assertEquals("ricetta carbonara", OmniboxRules.typedQuery("ricetta carbonara", 17, 17, false))
        assertEquals("", OmniboxRules.typedQuery("Cerca o digita l'indirizzo web", 0, 0, showsHint = true))
    }

    @Test
    fun `informativa in primo piano per Accessibilita e VPN`() {
        val a11y = Disclosures.Kind.ACCESSIBILITY.text
        assertTrue(a11y.startsWith("Informativa sull'uso di AccessibilityService: CutBet utilizza l'API AccessibilityService esclusivamente"))
        assertTrue(a11y.contains("Bot Telegram privato configurato dal tutore"))
        assertTrue(Disclosures.Kind.VPN.text.contains("VPN esclusivamente locale") && Disclosures.Kind.VPN.text.contains("DNS sinkhole"))
        assertEquals("Accetto e Continua", Disclosures.ACCEPT)
        assertEquals("Annulla", Disclosures.CANCEL)
    }

    @Test
    fun `modalita discreta con nomi conformi e componenti invariati`() {
        val labels = AppDisguise.Appearance.entries.map { it.homeLabel }
        assertEquals(listOf("CutBet", "CB Focus", "CutBet Utility"), labels)
        // Gli alias del manifest non cambiano nome: chi li aveva scelti non perde l'icona.
        assertEquals(setOf(".LauncherDefault", ".LauncherBattery", ".LauncherNetwork"), AppDisguise.Appearance.entries.map { it.alias }.toSet())
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        AppDisguise.Appearance.entries.forEach { assertTrue(it.alias, manifest.contains("android:name=\"${it.alias}\"")) }
        assertFalse("QUERY_ALL_PACKAGES", manifest.contains("QUERY_ALL_PACKAGES"))
        val strings = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.exists() }.readText()
        assertFalse(strings.contains("Servizi di Rete") || strings.contains("Gestione Batteria"))
    }
}
