package com.zerobet.safeguard.guard

/**
 * Identificativi personali che non vanno mai letti come parole: indirizzi email
 * ("tradingonmattia@inwind.it", "casinò.info@pec.it") e handle ("@betta_92"). Il nome di un
 * account non dice nulla sul contenuto della schermata. Kotlin puro, coperto da unit test.
 *
 * Le regole di Telegram, che DEVONO leggere gli inviti a bot e canali ("@CasinoBonusBot"),
 * lavorano sui testi originali e non passano da qui.
 */
object TextSanitizer {
    private val EMAIL = Regex("[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}\\-]+(?:\\.[\\p{L}\\p{N}\\-]+)*\\.\\p{L}{2,}")
    private val HANDLE = Regex("(?<![\\p{L}\\p{N}._%+\\-])@[\\p{L}\\p{N}_.]{2,}")

    fun stripIdentifiers(text: String): String =
        if (text.indexOf('@') < 0) text else HANDLE.replace(EMAIL.replace(text, " "), " ")
}

/** Client di posta e webmail. */
object MailApps {
    val PACKAGES = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "it.italiaonline.mail", "it.virgilio.mail",
        "com.yahoo.mobile.client.android.mail", "com.samsung.android.email.provider", "ch.protonmail.android",
        "de.gmx.mobile.android.mail", "com.android.email", "com.google.android.email", "me.bluemail.mail",
        "com.aol.mobile.aolapp", "it.tiscali.mail", "com.fsck.k9",
    )
    private val WEBMAIL_HOSTS = listOf(
        "mail.google.com", "mail.libero.it", "mail1.libero.it", "mail.virgilio.it", "outlook.live.com",
        "outlook.office.com", "mail.yahoo.com", "mail.tiscali.it", "mail.proton.me", "webmail.aruba.it",
    )

    fun isMail(packageName: String, host: String?): Boolean {
        if (packageName in PACKAGES) return true
        val h = host?.lowercase()?.removePrefix("www.") ?: return false
        return WEBMAIL_HOSTS.any { h == it || h.endsWith(".$it") }
    }
}

/**
 * Posta: logica a due stati. Kotlin puro, coperto da unit test.
 *
 *  - ELENCO (posta in arrivo, cartelle, risultati): non si esce MAI, anche se tutte le email visibili
 *    sono spam. Si oscurano solo le righe il cui oggetto/anteprima è a rischio; le altre restano
 *    leggibili e cliccabili.
 *  - DETTAGLIO (una sola email aperta): se l'email tratta di trading, scommesse o prestiti si fa
 *    Indietro (si torna all'elenco); se è innocua resta aperta, senza pannelli.
 * Nel dubbio la schermata è un elenco: meglio oscurare una riga che chiudere la posta.
 */
object MailRules {

    enum class View { INBOX, DETAIL }

    private const val MIN_ROWS = 2

    /** Pulsanti e voci tipiche dell'elenco: scrivere un'email, cercare, cartelle. */
    private val INBOX_MARKERS = listOf(
        "scrivi", "componi", "nuova email", "nuovo messaggio", "compose", "new message", "new mail",
        "cerca nella posta", "cerca nelle email", "cerca email", "search in mail", "search mail", "search email",
        "posta in arrivo", "inbox", "principale", "primary", "promozioni", "promotions", "cartelle", "folders",
        "posta inviata", "bozze", "drafts",
    )

    /** Pulsanti tipici del dettaglio di un messaggio. */
    private val DETAIL_MARKERS = listOf(
        "rispondi", "rispondi a tutti", "inoltra", "reply", "reply all", "forward", "a: ", "to: ", "da: ", "from: ",
    )

    fun viewOf(model: ScreenModel): View {
        val texts = model.items.flatMap { it.segments }.map { " " + it.lowercase().trim() + " " }
        val hasInboxMarker = texts.any { t -> INBOX_MARKERS.any { m -> t.trim() == m || t.contains(" $m ") } }
        val hasDetailMarker = texts.any { t -> DETAIL_MARKERS.any { m -> t.trim() == m.trim() || t.startsWith(" $m") } }
        val rows = model.items.count { it.role == ItemRole.CONTENT && it.segments.size >= 2 }
        return when {
            hasDetailMarker && !hasInboxMarker -> View.DETAIL
            hasDetailMarker && rows < MIN_ROWS -> View.DETAIL
            else -> View.INBOX
        }
    }

    fun decide(model: ScreenModel): GuardDecision {
        val view = viewOf(model)
        val content = model.items.filter { it.role == ItemRole.CONTENT }
        if (view == View.DETAIL) {
            // Oggetto e corpo dell'email aperta, valutati per segmenti e nell'insieme.
            val segments = model.items.flatMap { it.segments }
            val hit = InAppContentAndAdFilter.findContent(segments)
                ?: InAppContentAndAdFilter.findAd(segments)
                ?: ContentClassifier.classify(segments.joinToString("\n"))?.let {
                    InAppHit(InAppKind.CONTENT, it.category, it.phrases.firstOrNull() ?: it.category.label, segments.firstOrNull().orEmpty().take(80))
                }
            return if (hit != null) GuardDecision.CloseScreen(hit, "email a rischio aperta") else GuardDecision.None
        }
        // Elenco: si oscurano le sole righe a rischio, mai chiusura.
        val risky = content.mapNotNull { item ->
            val hit = InAppContentAndAdFilter.findAd(item.segments) ?: InAppContentAndAdFilter.findContent(item.segments)
            hit?.let { item to it }
        }
        if (risky.isEmpty()) return GuardDecision.None
        val boxes = risky.map { it.first.box.intersect(model.screen) }.filter { !it.isEmpty }.distinct()
        return if (boxes.isEmpty()) GuardDecision.None else GuardDecision.MaskRects(boxes, risky.map { it.second })
    }
}
