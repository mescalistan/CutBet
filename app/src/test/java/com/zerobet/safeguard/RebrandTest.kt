package com.zerobet.safeguard

import com.zerobet.safeguard.guard.BlockedPackages
import com.zerobet.safeguard.guard.ContentClassifier
import com.zerobet.safeguard.guard.InAppContentAndAdFilter
import com.zerobet.safeguard.guard.SearchQueryClassifier
import com.zerobet.safeguard.vpn.DomainHeuristics
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "CutBet" contiene "bet": il nome dell'app non deve mai far scattare i propri filtri (avvisi,
 * notifiche e schermate di CutBet sono letti dal Guardiano come qualsiasi altro testo).
 */
class RebrandTest {

    @Test
    fun `il nome CutBet non e' una ricerca o un contenuto a rischio`() {
        listOf("CutBet", "cutbet", "app CutBet", "come configurare CutBet", "CutBet recensioni").forEach {
            assertNull("Ricerca bloccata per errore: $it", SearchQueryClassifier.classify(it))
        }
        listOf(
            "CutBet: ricerca bloccata",
            "CutBet",
            "CutBet è attivo · Filtro DNS locale in funzione",
            "Questa sezione può disattivare CutBet. È richiesto il PIN del tutore.",
            "Questa app è bloccata da CutBet. Solo il tutore può aprirla, con il PIN.",
        ).forEach {
            assertNull("Contenuto bloccato per errore: $it", ContentClassifier.classify(it))
            assertNull("Testo in app bloccato per errore: $it", InAppContentAndAdFilter.classifyText(it))
        }
    }

    @Test
    fun `l'app stessa non e' mai bloccata, qualunque nome mostri`() {
        listOf("CutBet", "Servizi di Rete", "Gestione Batteria").forEach {
            assertNull(BlockedPackages.categoryOf("com.zerobet.safeguard", it))
        }
    }

    @Test
    fun `un dominio cutbet non e' a rischio per l'euristica`() {
        assertTrue(DomainHeuristics.score("cutbet.app") < DomainHeuristics.THRESHOLD)
    }
}
