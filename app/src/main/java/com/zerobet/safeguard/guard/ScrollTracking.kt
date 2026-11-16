package com.zerobet.safeguard.guard

/**
 * Pannelli che seguono lo scorrimento. Ogni pannello è agganciato al nodo dell'annuncio che copre:
 * durante lo scorrimento si rilegge solo la posizione di quel nodo e il pannello lo ricopre
 * (stesso margine), limitato all'area visibile della lista/pagina. Kotlin puro, coperto da unit
 * test (anche di velocità: un calcolo costa pochi decimi di microsecondo).
 */
object ScrollTracking {

    const val MIN_VISIBLE_PX = 12

    /**
     * @param mask box coperto dal pannello quando è stato calcolato
     * @param anchorThen posizione del nodo in quel momento, [anchorNow] adesso
     * @param viewport area visibile in cui il pannello deve restare (lista, pagina web, schermo)
     * @return il nuovo box, o null se è uscito (o quasi) dall'area visibile
     */
    fun relocate(mask: Box, anchorThen: Box, anchorNow: Box, viewport: Box): Box? {
        if (anchorNow.isEmpty) return null
        // Il pannello si ricostruisce dal nodo com'è adesso, con lo stesso margine attorno. Non si
        // sposta il vecchio box: se era tagliato dal bordo (banner entrato a metà) resterebbe una
        // striscia e la parte del banner che entra nello schermo resterebbe scoperta.
        // Margine di ogni lato; un lato tagliato dal bordo non lo conosce e usa quello opposto.
        val left = anchorThen.left - mask.left
        val right = mask.right - anchorThen.right
        val top = anchorThen.top - mask.top
        val bottom = mask.bottom - anchorThen.bottom
        val moved = Box(
            anchorNow.left - (if (left >= 0) left else maxOf(0, right)),
            anchorNow.top - (if (top >= 0) top else maxOf(0, bottom)),
            anchorNow.right + (if (right >= 0) right else maxOf(0, left)),
            anchorNow.bottom + (if (bottom >= 0) bottom else maxOf(0, top)),
        )
        val visible = moved.intersect(viewport)
        return if (visible.width < MIN_VISIBLE_PX || visible.height < MIN_VISIBLE_PX) null else visible
    }
}
