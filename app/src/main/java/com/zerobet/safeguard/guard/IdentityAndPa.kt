package com.zerobet.safeguard.guard

/**
 * SPID, CIE, identità digitale e Pubblica Amministrazione: esenzione totale, qualunque banca abbia
 * scelto il tutore. Sono servizi essenziali per il cittadino e non offrono mai gioco o trading:
 * le app non vengono mai chiuse né oscurate, i domini non finiscono mai nel sinkhole DNS e le
 * pagine di login SPID/CIE non subiscono blocchi lessicali né pannelli (pulsante "Entra con SPID",
 * QR code e notifica di autorizzazione restano sempre visibili). Kotlin puro, coperto da unit test.
 */
object IdentityAndPa {

    /** App sempre consentite e mai analizzate (zero chiusure, zero pannelli). */
    val ALWAYS_ALLOWED_IDENTITY_AND_PA_PACKAGES: Set<String> = setOf(
        // PosteID: libera anche se la banca scelta non è Poste Italiane
        "posteitaliane.posteapp.appposteid",
        // CIE ID e app della PA
        "it.ipzs.cieid", "it.pagopa.io.app", "it.inps.mobile", "it.agenziaentrate.mobile",
        "it.ministerodellasalute.immuni", "it.sogei.tesserasanitaria",
        // altri Identity Provider SPID
        "it.aruba.spid.id", "it.infocert.myinfocert", "com.sielte.mysielteid", "it.tim.timid", "it.namirial.spid",
        "it.lepida.lepidaid", "it.register.spid", "it.teamsystem.id", "it.intesi.spid",
        // autenticatori a due fattori
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.duosecurity.duomobile",
    )

    fun isExemptPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg in ALWAYS_ALLOWED_IDENTITY_AND_PA_PACKAGES ||
            ALWAYS_ALLOWED_IDENTITY_AND_PA_PACKAGES.any { pkg.startsWith("$it.") }
    }

    /** Domini (con tutti i sottodomini) di PA, sanità e Identity Provider SPID/CIE. */
    private val DOMAINS: Set<String> = setOf(
        "inps.it", "agenziaentrate.gov.it", "agenziaentrateriscossione.gov.it", "pagopa.gov.it", "pagopa.it",
        "fascicolosanitario.gov.it", "sanita.it", "sistemats.it", "anpr.interno.it", "interno.it",
        "spid.gov.it", "posteid.poste.it", "cieid.interno.gov.it", "id.lepida.it", "id.infocert.it",
        "idserver.servizicie.interno.gov.it", "spid.aruba.it", "myid.sieltecloud.it", "spid.register.it",
        "id.tim.it", "idp.namirialtsp.com", "spid.intesi.it", "spid.teamsystem.com",
    )

    /** Domini istituzionali per suffisso: qualsiasi "*.gov.it" ed "*.edu.it". */
    private val INSTITUTIONAL_SUFFIXES = listOf(".gov.it", ".edu.it")

    /** Comuni e Regioni: "comune.milano.it", "www.comune.roma.it", "regione.lombardia.it". */
    private val LOCAL_GOV = Regex("(?:^|\\.)(?:comune|regione)\\.[a-z0-9\\-]+\\.it$")

    /**
     * Sottodomini di poste.it usati dal login PosteID/SPID: liberi anche quando poste.it è bloccato
     * perché la banca scelta è un'altra (le pagine BancoPosta dei prestiti restano bloccate).
     */
    private val POSTE_LOGIN_TOKENS = listOf("posteid", "spid", "saml", "oauth")

    fun isExemptDomain(rawHost: String?): Boolean {
        val host = rawHost?.lowercase()?.trim()?.trimEnd('.')?.removePrefix("www.") ?: return false
        if (host.isEmpty()) return false
        if (INSTITUTIONAL_SUFFIXES.any { host.endsWith(it) || host == it.substring(1) }) return true
        if (LOCAL_GOV.containsMatchIn(host)) return true
        if (host.endsWith(".poste.it") && POSTE_LOGIN_TOKENS.any { host.removeSuffix(".poste.it").contains(it) }) return true
        var d = host
        while (true) {
            if (d in DOMAINS) return true
            val dot = d.indexOf('.')
            if (dot < 0) return false
            d = d.substring(dot + 1)
            if (d.indexOf('.') < 0) return false
        }
    }

    /**
     * Pagina (o indirizzo) di poste.it che fa parte del flusso di login PosteID/SPID: nel browser
     * conta l'URL intero ("www.poste.it/...posteid...", "...?SAMLRequest=..."), non solo l'host.
     */
    fun isExemptUrl(host: String?, url: String?): Boolean {
        if (isExemptDomain(host)) return true
        val h = host?.lowercase()?.removePrefix("www.") ?: return false
        val u = url?.lowercase() ?: return false
        return (h == "poste.it" || h.endsWith(".poste.it")) && POSTE_LOGIN_TOKENS.any { u.contains(it) }
    }

    /** Segni di una pagina di login SPID/CIE, anche dentro la WebView di un'app qualsiasi. */
    private val LOGIN_MARKERS = listOf(
        "entra con spid", "entra con cie", "autorizza accesso spid", "posteid", "livellospid", "samlrequest",
        "accedi con spid", "accedi con cie",
    )

    fun isLoginPage(texts: Iterable<String>, url: String? = null): Boolean {
        url?.lowercase()?.let { u -> if (LOGIN_MARKERS.any { u.contains(it.replace(" ", "")) } || u.contains("samlrequest")) return true }
        for (t in texts) {
            val s = t.lowercase()
            if (LOGIN_MARKERS.any { s.contains(it) }) return true
        }
        return false
    }
}
