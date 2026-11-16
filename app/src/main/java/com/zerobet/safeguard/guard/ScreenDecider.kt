package com.zerobet.safeguard.guard

/** Rettangolo in coordinate dello schermo (come getBoundsInScreen), senza dipendere da android.graphics. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun intersect(other: Box): Box =
        Box(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
}

/** Ruolo di un elemento della schermata. */
enum class ItemRole {
    /** Intestazione: barra degli indirizzi, titolo della pagina o dell'app, nome del gruppo/canale. */
    HEADER,
    /** Singola card, post, box o banner dentro una lista, uno scroll o una pagina web. */
    CONTENT,
}

/** Un contenitore della schermata con i testi del suo sottoalbero. */
data class ScreenItem(val segments: List<String>, val box: Box, val role: ItemRole)

data class ScreenModel(
    val items: List<ScreenItem>,
    /** Area visibile dello schermo. */
    val screen: Box,
    /** App o sito di annunci/marketplace (Subito, eBay, Vinted, AutoScout24, Amazon). */
    val marketplace: Boolean = false,
)

/** Che cosa fare della schermata. */
sealed class GuardDecision {
    data object None : GuardDecision()

    /** Il contenuto principale è vietato: si esce (Indietro, poi Home). */
    data class CloseScreen(val hit: InAppHit, val reason: String) : GuardDecision()

    /** Contesto innocuo: si oscurano solo i box indicati, l'app resta utilizzabile. */
    data class MaskRects(val boxes: List<Box>, val hits: List<InAppHit>) : GuardDecision()
}

/**
 * Decide tra USCIRE e OSCURARE guardando la schermata per contenitori, non come un unico testo:
 * un banner a fondo pagina non deve più contaminare un articolo innocuo. Kotlin puro, coperto da
 * unit test.
 *
 * Esce (CloseScreen) quando è il contenuto scelto dall'utente a essere a tema:
 *  - intestazione a rischio (titolo della pagina, del gruppo, del canale);
 *  - destinazione dedicata: "Gruppo pubblico · Trading e Investimenti", "12.000 membri" vicino a un
 *    titolo a rischio;
 *  - contenuto principale a rischio (un box che occupa almeno il 75% dello schermo);
 *  - contenuti a rischio prevalenti (molti post o video sul tema: una community, una pagina di video).
 * Oscura (MaskRects) i singoli annunci e box a rischio dentro un contesto innocuo.
 * Nei marketplace non si esce mai per i contenuti: solo per la ricerca (gestita a parte) o per il
 * modulo di richiesta di un finanziamento aperto dall'utente.
 */
object ScreenDecider {

    const val MAIN_CONTENT_RATIO = 0.75
    const val DOMINANT_AREA_RATIO = 0.35
    private const val MAX_HEADER_TITLE = 70
    private const val MIN_MASK_SIDE = 12
    /** Contenitori prima e dopo la riga "Gruppo pubblico · membri" in cui cercare il titolo. */
    private const val DESTINATION_WINDOW = 2

    /** Segni che un elemento è l'intestazione di una destinazione (gruppo, pagina, canale, community). */
    private val DESTINATION_MARKERS = listOf(
        "gruppo pubblico", "gruppo privato", "public group", "private group", "membri", "members",
        "follower", "followers", "iscritti", "subscribers", "mi piace alla pagina", "community",
        "canale", "channel", "unisciti al gruppo", "join group", "pagina ·", "page ·",
    )

    /** Titoli del modulo di richiesta di un finanziamento: unico caso di uscita in un marketplace. */
    private val LOAN_FORM_TITLES = listOf(
        "richiedi finanziamento", "richiedi il finanziamento", "richiesta di finanziamento", "richiesta finanziamento",
        "richiedi prestito", "richiedi il prestito", "richiedi un prestito", "richiesta di prestito",
        "modulo di finanziamento", "domanda di finanziamento", "apply for financing", "apply for a loan",
    )

    fun decide(model: ScreenModel): GuardDecision {
        val screenArea = model.screen.area.coerceAtLeast(1)

        // 1) Intestazione: titolo della pagina, dell'app o della destinazione aperta.
        for (item in model.items) {
            if (item.role != ItemRole.HEADER) continue
            for (segment in item.segments) {
                if (model.marketplace) {
                    if (isLoanFormTitle(segment)) return close(segment, ContentCategory.LOANS, "modulo di finanziamento")
                    continue
                }
                headerHit(segment)?.let { return GuardDecision.CloseScreen(it, "intestazione a rischio") }
            }
        }

        val content = model.items.filter { it.role == ItemRole.CONTENT }
        // 2) Destinazione dedicata: il titolo di un gruppo/pagina/canale a rischio. Titolo e riga
        //    "Gruppo pubblico · 12.300 membri" possono stare in contenitori vicini (pagine web).
        if (!model.marketplace) {
            content.forEachIndexed { index, item ->
                if (!isDestinationHeader(item.segments)) return@forEachIndexed
                for (j in maxOf(0, index - DESTINATION_WINDOW)..minOf(content.size - 1, index + DESTINATION_WINDOW)) {
                    val near = content[j].segments
                    (InAppContentAndAdFilter.findContent(near) ?: near.firstNotNullOfOrNull { s -> s.takeIf { it.length <= MAX_HEADER_TITLE }?.let(::headerHit) })
                        ?.let { return GuardDecision.CloseScreen(it, "gruppo o pagina a tema") }
                }
            }
        }
        val risky = ArrayList<Pair<ScreenItem, InAppHit>>()
        for (item in content) {
            val hit = InAppContentAndAdFilter.findAd(item.segments) ?: InAppContentAndAdFilter.findContent(item.segments) ?: continue
            risky += item to hit
        }

        // 2b) Annuncio diviso su più contenitori ("Sponsorizzato" in una riga, il marchio in quella dopo).
        if (risky.isEmpty()) {
            spanAd(content)?.let { risky += it }
        }
        if (risky.isEmpty()) return GuardDecision.None

        if (!model.marketplace) {
            // 3) Contenuto principale (quasi tutto lo schermo) a rischio.
            risky.firstOrNull { (item, hit) ->
                hit.kind == InAppKind.CONTENT && item.box.height >= model.screen.height * MAIN_CONTENT_RATIO
            }?.let { return GuardDecision.CloseScreen(it.second, "contenuto principale a rischio") }

            // 4) Contenuti a rischio prevalenti: una pagina o un feed dedicato al tema.
            val riskyContent = risky.filter { it.second.kind == InAppKind.CONTENT }
            val riskyArea = riskyContent.sumOf { it.first.box.intersect(model.screen).area.coerceAtLeast(0) }
            val textItems = content.count { it.segments.any { s -> s.length >= 8 } }.coerceAtLeast(1)
            if (riskyArea >= screenArea * DOMINANT_AREA_RATIO ||
                (riskyContent.size >= 2 && riskyContent.size * 2 >= textItems)
            ) {
                return GuardDecision.CloseScreen(riskyContent.first().second, "contenuti a rischio prevalenti")
            }
        }

        // 5) Contesto innocuo: si oscurano i soli box (annunci, widget "Calcola rata", post isolati),
        //    insieme all'etichetta "Sponsorizzato" se sta nel contenitore subito sopra o sotto.
        val boxes = risky.map { (item, _) -> withAdjacentAdLabel(item, content).intersect(model.screen) }
            .filter { it.width >= MIN_MASK_SIDE && it.height >= MIN_MASK_SIDE }
            .distinct()
        if (boxes.isEmpty()) return GuardDecision.None
        return GuardDecision.MaskRects(boxes, risky.map { it.second })
    }

    /** Titolo di una pagina/app/gruppo a rischio: le regole delle ricerche su un testo breve, o quelle dei contenuti. */
    fun headerHit(segment: String): InAppHit? {
        val text = segment.trim()
        if (text.length < 3) return null
        if (text.length <= MAX_HEADER_TITLE) {
            SearchQueryClassifier.classify(text)?.let { return InAppHit(InAppKind.CONTENT, it.category, it.term, text.take(80)) }
        }
        return InAppContentAndAdFilter.classifyText(text)
    }

    fun isDestinationHeader(segments: List<String>): Boolean = segments.any { s ->
        val t = " " + s.lowercase() + " "
        DESTINATION_MARKERS.any { t.contains(it) }
    }

    fun isLoanFormTitle(segment: String): Boolean {
        val t = SearchQueryClassifier.normalize(segment)
        return t.length <= 80 && LOAN_FORM_TITLES.any { t == it || t.startsWith("$it ") }
    }

    private fun withAdjacentAdLabel(item: ScreenItem, content: List<ScreenItem>): Box {
        val index = content.indexOf(item)
        if (index < 0) return item.box
        var box = item.box
        for (neighbor in listOfNotNull(content.getOrNull(index - 1), content.getOrNull(index + 1))) {
            val onlyLabels = neighbor.segments.isNotEmpty() && neighbor.segments.all { InAppContentAndAdFilter.isAdMarker(it) }
            if (onlyLabels) {
                box = Box(minOf(box.left, neighbor.box.left), minOf(box.top, neighbor.box.top),
                    maxOf(box.right, neighbor.box.right), maxOf(box.bottom, neighbor.box.bottom))
            }
        }
        return box
    }

    private fun close(segment: String, category: ContentCategory, reason: String) =
        GuardDecision.CloseScreen(InAppHit(InAppKind.CONTENT, category, reason, segment.take(80)), reason)

    /** Annuncio con etichetta e testo in contenitori vicini: si oscura dall'etichetta al testo. */
    private fun spanAd(content: List<ScreenItem>): Pair<ScreenItem, InAppHit>? {
        val flat = ArrayList<String>()
        val owner = ArrayList<Int>()
        content.forEachIndexed { index, item -> item.segments.forEach { flat += it; owner += index } }
        val (hit, range) = InAppContentAndAdFilter.findAdSpan(flat) ?: return null
        val items = (owner[range.first]..owner[range.last]).map { content[it] }
        if (items.size > 4) return null
        val box = Box(
            items.minOf { it.box.left }, items.minOf { it.box.top },
            items.maxOf { it.box.right }, items.maxOf { it.box.bottom },
        )
        return ScreenItem(items.flatMap { it.segments }, box, ItemRole.CONTENT) to hit
    }
}

/**
 * App e siti di annunci / e-commerce: le schede di prodotti normali mostrano quasi sempre "Calcola
 * rata", "Paga a rate", "TAN/TAEG" o banner pubblicitari. Lì non si esce mai per i contenuti: si
 * oscurano i box (vedi [ScreenDecider]).
 */
object Marketplaces {
    val PACKAGES = setOf(
        "it.subito", "com.ebay.mobile", "fr.vinted", "com.autoscout24", "com.amazon.mShop.android.shopping",
        "com.amazon.mshop.android.shopping",
    )
    private val HOSTS = listOf(
        "subito.it", "ebay.it", "ebay.com", "vinted.it", "vinted.com", "autoscout24.it", "autoscout24.com", "amazon.it", "amazon.com",
    )

    fun isMarketplace(packageName: String, host: String?): Boolean {
        if (packageName in PACKAGES) return true
        val h = host?.lowercase()?.removePrefix("www.") ?: return false
        return HOSTS.any { h == it || h.endsWith(".$it") }
    }
}
