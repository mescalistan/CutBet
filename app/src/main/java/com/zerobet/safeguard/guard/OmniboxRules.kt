package com.zerobet.safeguard.guard

/**
 * Barra di ricerca/indirizzi del browser aperta (tastiera e suggerimenti), Nuova scheda e pagina
 * della cronologia (`chrome://history`). Kotlin puro, coperto da unit test.
 *
 * Decide SOLO il testo scritto nella barra: se è una ricerca vietata si esce, come per qualsiasi
 * ricerca. Le righe di suggerimento e di cronologia sotto la barra non fanno MAI chiudere il browser:
 * una vecchia ricerca a rischio ("snai scommesse") viene coperta dal pannello oscurante, riga per
 * riga come nella posta, e l'utente può continuare a scrivere e cercare altro.
 */
object OmniboxRules {

    /** Pagine interne del browser che mostrano cronologia e suggerimenti, mai contenuti da chiudere. */
    private val INTERNAL_PAGES = listOf("chrome://", "about:", "edge://", "brave://", "opera://", "samsung://", "chrome-native://")

    /**
     * true se il browser mostra la barra in modifica, la Nuova scheda (barra vuota o con il testo
     * segnaposto) o una pagina interna come la cronologia.
     */
    fun isOmniboxState(barFocused: Boolean, barText: String?, barShowsHint: Boolean): Boolean {
        if (barFocused || barShowsHint) return true
        val text = barText?.trim().orEmpty()
        if (text.isEmpty()) return true
        val lower = text.lowercase()
        return INTERNAL_PAGES.any { lower.startsWith(it) }
    }

    /**
     * Testo davvero scritto dall'utente: il completamento automatico del browser ("tr" ->
     * "trading online" preso dalla cronologia) sta DOPO il cursore e non conta. Chrome lo espone
     * come testo intero con il cursore subito dopo le lettere scritte (selezione 2..2), altri browser
     * come testo selezionato (2..14): in entrambi i casi conta solo ciò che precede il cursore.
     * Il testo segnaposto non conta mai.
     */
    fun typedQuery(barText: String?, selectionStart: Int, selectionEnd: Int, showsHint: Boolean): String {
        if (showsHint) return ""
        val text = barText.orEmpty()
        val caret = minOf(selectionStart, selectionEnd)
        val cut = if (caret >= 0) caret.coerceAtMost(text.length) else text.length
        return text.substring(0, cut).trim()
    }

    /**
     * @param query testo scritto nella barra (vedi [typedQuery]); vuoto se non si sta scrivendo
     * @param rows contenitori a schermo (righe dei suggerimenti, voci della cronologia). Si valutano
     *   tutti, anche quelli in alto che il modello chiama "intestazione": nell'omnibox i primi
     *   suggerimenti stanno proprio sotto la barra.
     * @param barBox posizione della barra: non si giudica né si oscura mai (ci si deve poter scrivere),
     *   anche quando il completamento automatico vi mostra la stessa vecchia ricerca della prima riga.
     */
    fun decide(query: String, rows: List<ScreenItem>, screen: Box, barBox: Box? = null): GuardDecision {
        if (query.isNotEmpty()) {
            SearchQueryClassifier.classify(query, typing = true)?.let { hit ->
                return GuardDecision.CloseScreen(
                    InAppHit(InAppKind.CONTENT, hit.category, hit.term, query.take(80)), "ricerca vietata nella barra"
                )
            }
        }
        val risky = rows.mapNotNull { row ->
            if (barBox != null && !row.box.intersect(barBox).isEmpty) return@mapNotNull null
            // La riga che ripete esattamente il testo scritto non si giudica di nuovo (ricerca in corso).
            if (query.isNotEmpty() && row.segments.all { it.trim().equals(query, ignoreCase = true) }) return@mapNotNull null
            rowHit(row.segments)?.let { row to it }
        }
        if (risky.isEmpty()) return GuardDecision.None
        val targets = risky.map { it.first to it.first.box.intersect(screen) }.filter { !it.second.isEmpty }.distinctBy { it.second }
        return if (targets.isEmpty()) GuardDecision.None
        else GuardDecision.MaskRects(targets.map { it.second }, risky.map { it.second }, targets.map { it.first })
    }

    /** Una riga di suggerimento è una ricerca (o un titolo di pagina): si valuta come una ricerca. */
    private fun rowHit(segments: List<String>): InAppHit? {
        for (s in segments) {
            val text = s.trim()
            if (text.isEmpty()) continue
            SearchQueryClassifier.classify(text)?.let { return InAppHit(InAppKind.CONTENT, it.category, it.term, text.take(80)) }
        }
        return InAppContentAndAdFilter.findAd(segments) ?: InAppContentAndAdFilter.findContent(segments)
    }
}
