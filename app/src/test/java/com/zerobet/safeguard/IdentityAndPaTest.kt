package com.zerobet.safeguard

import com.zerobet.safeguard.data.DefaultData
import com.zerobet.safeguard.data.WhitelistedBank
import com.zerobet.safeguard.guard.BlockedPackages
import com.zerobet.safeguard.guard.IdentityAndPa
import com.zerobet.safeguard.vpn.DomainFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityAndPaTest {

    /** Filtro con le regole predefinite (banche comprese) e la sola UniCredit in whitelist: poste.it è bloccato. */
    private val unicreditOnly = DomainFilter(
        listOf(WhitelistedBank(name = "UniCredit", domain = "unicredit.it")),
        DefaultData.seedEntries() + DefaultData.seedEntriesV2(),
    )

    @Test
    fun `posteid poste it libero anche se la banca scelta non e Poste`() {
        assertTrue("poste.it generico resta bloccato", unicreditOnly.check("poste.it").blocked)
        assertTrue(unicreditOnly.check("www.poste.it").blocked)
        assertFalse(unicreditOnly.check("posteid.poste.it").blocked)
        assertFalse(unicreditOnly.check("idp-posteid.poste.it").blocked)
        assertFalse(unicreditOnly.check("spid.poste.it").blocked)
        // URL del login PosteID su www.poste.it nel browser: esente; la pagina dei prestiti no.
        assertTrue(IdentityAndPa.isExemptUrl("poste.it", "https://www.poste.it/posteid/login?SAMLRequest=abc"))
        assertFalse(IdentityAndPa.isExemptUrl("poste.it", "https://www.poste.it/prestiti/prestito-bancoposta.html"))
    }

    @Test
    fun `domini istituzionali e Identity Provider sempre consentiti`() {
        listOf(
            "www.spid.gov.it", "www.agenziaentrate.gov.it", "idserver.servizicie.interno.gov.it", "www.miur.edu.it",
            "servizi.inps.it", "www.inps.it", "checkout.pagopa.it", "www.pagopa.gov.it", "www.sistemats.it",
            "www.comune.milano.it", "comune.roma.it", "www.regione.lombardia.it", "anpr.interno.it", "id.lepida.it",
            "spid.aruba.it", "myid.sieltecloud.it", "idp.namirialtsp.com", "id.tim.it", "spid.register.it",
        ).forEach { assertTrue(it, IdentityAndPa.isExemptDomain(it)) }
        // Nomi che somigliano ma non sono della PA.
        listOf("gov.it.casino.com", "comune-casino.it", "inps-bonus-scommesse.com", "pagopa.it.bet", "snai.it", "poste.it")
            .forEach { assertFalse(it, IdentityAndPa.isExemptDomain(it)) }
        // Nel filtro DNS l'esenzione vince anche su liste e parole chiave.
        val hostile = DomainFilter(emptySet(), setOf("gov.it", "inps.it"), listOf("spid"))
        assertFalse(hostile.check("www.inps.it").blocked)
        assertFalse(hostile.check("www.spid.gov.it").blocked)
        assertTrue(hostile.check("spidcasino.com").blocked)
    }

    @Test
    fun `app SPID CIE e PA consentite con qualsiasi banca`() {
        val unicredit = BlockedPackages.bankAppsFor(listOf("unicredit.it"))
        val allowed = setOf("unicredit")
        listOf(
            "posteitaliane.posteapp.appposteid", "it.ipzs.cieid", "it.pagopa.io.app", "it.inps.mobile",
            "it.agenziaentrate.mobile.servizionline.ServiziActivity", "it.sogei.tesserasanitaria", "it.aruba.spid.id",
            "it.infocert.myinfocert", "com.sielte.mysielteid", "com.google.android.apps.authenticator2",
            "com.azure.authenticator", "com.duosecurity.duomobile",
        ).forEach {
            assertTrue(it, IdentityAndPa.isExemptPackage(it))
            assertNull(it, BlockedPackages.categoryOf(it, "PosteID", allowed, unicredit))
        }
        // Le app bancarie di Poste restano bloccate se la banca scelta è un'altra.
        assertTrue(BlockedPackages.categoryOf("posteitaliane.posteapp.appbpol", null, allowed, unicredit) != null)
        assertFalse(IdentityAndPa.isExemptPackage("it.snai.sport"))
    }

    @Test
    fun `pagina di login SPID e CIE riconosciuta`() {
        assertTrue(IdentityAndPa.isLoginPage(listOf("Accedi ai servizi", "Entra con SPID", "Entra con CIE")))
        assertTrue(IdentityAndPa.isLoginPage(listOf("Autorizza accesso SPID"), null))
        assertTrue(IdentityAndPa.isLoginPage(emptyList(), "https://idp.example.it/sso?SAMLRequest=xyz&LivelloSPID=2"))
        assertFalse(IdentityAndPa.isLoginPage(listOf("Bonus benvenuto 500€", "Scommetti ora")))
    }
}
