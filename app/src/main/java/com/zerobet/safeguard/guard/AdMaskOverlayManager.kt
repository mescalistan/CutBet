package com.zerobet.safeguard.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.mask.CustomMaskImageStore
import com.zerobet.safeguard.mask.MaskLayout
import com.zerobet.safeguard.mask.MaskPanelViews
import com.zerobet.safeguard.mask.MaskPolicy
import com.zerobet.safeguard.mask.PhotoRotation

/**
 * Pannelli opachi sopra i singoli banner e box a rischio, al posto di chiudere l'app.
 *
 * Finestre TYPE_ACCESSIBILITY_OVERLAY: un servizio di accessibilità le può aggiungere senza il
 * permesso "Mostra sopra altre app". Flag NOT_FOCUSABLE + NOT_TOUCH_MODAL: i tocchi fuori dal
 * pannello arrivano all'app (si scorre e si usa tutto il resto); un tocco sul pannello viene
 * consumato, così non apre il link pubblicitario. Un trascinamento che parte dal pannello invece
 * viene rigiocato sull'app con dispatchGesture (pannelli momentaneamente non toccabili): la pagina
 * scorre anche se il dito parte sopra il banner.
 *
 * I pannelli seguono lo scorrimento (spostamento immediato) e vengono rimossi subito quando
 * cambia app o schermata: mai pannelli "fantasma" sulla Home o sulla schermata di blocco.
 * Va usato solo dal thread principale.
 */
class AdMaskOverlayManager(private val service: AccessibilityService) {

    /** Pannello mostrato: [layout], foto e dimensioni decidono se la vista va ricostruita. */
    private class Panel(
        val view: View, val params: WindowManager.LayoutParams, var box: Box,
        val layout: MaskLayout, val photo: String?, val label: String, val caption: String,
        var hidden: Boolean = false,
    )

    /** Foto del tutore ("ancoraggio affettivo"), se l'app ne ha. */
    private val photos: CustomMaskImageStore? = (service.application as? SafeGuardApp)?.maskImages

    /** Foto e frase con cui sono stati costruiti i pannelli a schermo. */
    private var shownSettings: CustomMaskImageStore.Settings? = null
    private val rotation = PhotoRotation()

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val panels = ArrayList<Panel>()
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
    private val mainHandler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** App su cui sono mostrati i pannelli (null se nessuno). */
    var ownerPackage: String? = null
        private set

    val isShowing: Boolean get() = panels.isNotEmpty()

    /**
     * Riporta i pannelli esattamente sui [boxes] indicati, riusando le finestre esistenti.
     * [marginDp]: margine di sicurezza intorno al box (0 nelle liste, per non coprire le righe vicine).
     * @return true se qualcosa è cambiato (nuovi pannelli o posizioni diverse).
     */
    fun show(packageName: String, boxes: List<Box>, label: String, marginDp: Float = MARGIN_DP): Boolean {
        if (boxes.isEmpty()) {
            val had = panels.isNotEmpty()
            clear()
            return had
        }
        ownerPackage = packageName
        margin = (marginDp * density).toInt()
        val settings = photos?.settings?.value
        // Stesse posizioni e stesse foto/frase: nessun lavoro. Se il tutore cambia foto o frase
        // (anche dal bot) i pannelli già a schermo vengono ricostruiti subito.
        // (Un pannello nascosto dallo scorrimento conserva il vecchio box: va comunque rimostrato.)
        if (settings == shownSettings && boxes.size == panels.size && boxes.zip(panels).all { (b, p) -> b == p.box && !p.hidden }) return false
        shownSettings = settings
        while (panels.size > boxes.size) remove(panels.removeAt(panels.size - 1))
        boxes.forEachIndexed { i, box ->
            val m = margin
            val height = box.height + 2 * m
            val layout = MaskPolicy.layoutFor(height, density, hasPhotos = settings?.images?.isNotEmpty() == true, enabled = settings?.enabled == true)
            // Ogni pannello tiene la sua foto finché la schermata non cambia: niente lampeggi.
            val photo = if (layout == MaskLayout.DEFAULT_DARK || settings == null) null
            else settings.images.getOrNull(rotation.photoFor(i, settings.images.size))
            val panel = panels.getOrNull(i)
            val caption = settings?.caption.orEmpty()
            val sameLook = panel != null && panel.layout == layout && panel.photo == photo && panel.label == label &&
                (layout == MaskLayout.DEFAULT_DARK || panel.caption == caption) &&
                panel.box.width == box.width && panel.box.height == box.height
            when {
                panel == null -> add(box, label, layout, photo, settings?.caption.orEmpty())?.let { panels += it }
                sameLook -> place(i, box)
                else -> {
                    remove(panel)
                    val fresh = add(box, label, layout, photo, settings?.caption.orEmpty())
                    if (fresh != null) panels[i] = fresh else panels.removeAt(i)
                }
            }
        }
        return true
    }

    /**
     * Scorrimento: sposta subito i pannelli di [dy] pixel (il contenuto si è mosso di -dy) e rimuove
     * quelli usciti dallo schermo. La posizione esatta viene poi riletta dall'analisi successiva.
     */
    fun offset(dy: Int, screenHeight: Int) {
        if (dy == 0 || panels.isEmpty()) return
        val it = panels.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val moved = Box(p.box.left, p.box.top - dy, p.box.right, p.box.bottom - dy)
            if (moved.bottom <= 0 || moved.top >= screenHeight) {
                remove(p)
                it.remove()
            } else {
                move(p, moved)
            }
        }
        if (panels.isEmpty()) ownerPackage = null
    }

    /**
     * Sposta il pannello [index] su [box] riusando la sua finestra (nessuna vista ricreata),
     * o lo nasconde subito (null) se il banner è uscito dall'area visibile. Chiamato a ogni
     * evento di scorrimento: solo updateViewLayout, nessun calcolo pesante.
     */
    fun place(index: Int, box: Box?) {
        val p = panels.getOrNull(index) ?: return
        if (box == null) {
            if (p.hidden) return
            p.hidden = true
            p.view.visibility = View.GONE
            p.params.flags = p.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try {
                windowManager.updateViewLayout(p.view, p.params)
            } catch (e: Exception) {
                // già rimosso
            }
            return
        }
        if (p.hidden) {
            p.hidden = false
            p.view.visibility = View.VISIBLE
            p.params.flags = p.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else if (box == p.box) {
            return
        }
        move(p, box)
    }

    val panelCount: Int get() = panels.size

    fun clear() {
        panels.forEach { remove(it) }
        panels.clear()
        ownerPackage = null
        rotation.reset()
    }

    private var margin = (MARGIN_DP * density).toInt()

    private fun layoutFor(box: Box, params: WindowManager.LayoutParams) {
        val m = margin
        params.x = box.left - m
        params.y = box.top - m
        params.width = box.width + 2 * m
        params.height = box.height + 2 * m
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun add(box: Box, label: String, layout: MaskLayout, photo: String?, caption: String): Panel? {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "CutBetAdMask"
        }
        layoutFor(box, params)
        val view = buildView(params.height, label, layout, photo, caption)
        // Tocco consumato (il link pubblicitario sotto non si apre); trascinamento rigiocato sull'app.
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    downAt = e.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dx * dx + dy * dy > touchSlop * touchSlop) replaySwipe(downX, downY, e.rawX, e.rawY, e.eventTime - downAt)
                }
            }
            true
        }
        return try {
            windowManager.addView(view, params)
            Panel(view, params, box, layout, photo, label, caption)
        } catch (e: Exception) {
            Log.w(TAG, "Pannello non mostrato: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun move(panel: Panel, box: Box) {
        panel.box = box
        layoutFor(box, panel.params)
        try {
            windowManager.updateViewLayout(panel.view, panel.params)
        } catch (e: Exception) {
            Log.w(TAG, "Pannello non aggiornato: ${e.javaClass.simpleName}")
        }
    }

    /** Rigioca sull'app lo scorrimento iniziato sopra un pannello. */
    private fun replaySwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        setTouchable(false)
        val path = Path().apply {
            moveTo(x1.coerceAtLeast(0f), y1.coerceAtLeast(0f))
            lineTo(x2.coerceAtLeast(0f), y2.coerceAtLeast(0f))
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(60, 600)))
            .build()
        val restore = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { mainHandler.post { setTouchable(true) } }
            override fun onCancelled(gestureDescription: GestureDescription?) { mainHandler.post { setTouchable(true) } }
        }
        val started = try {
            service.dispatchGesture(gesture, restore, mainHandler)
        } catch (e: Exception) {
            false
        }
        if (!started) setTouchable(true)
    }

    private fun setTouchable(touchable: Boolean) {
        for (p in panels) {
            if (p.hidden) continue    // un pannello nascosto non deve mai intercettare i tocchi
            val flags = if (touchable) p.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else p.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (flags == p.params.flags) continue
            p.params.flags = flags
            try {
                windowManager.updateViewLayout(p.view, p.params)
            } catch (e: Exception) {
                // pannello già rimosso
            }
        }
    }

    private fun remove(panel: Panel) {
        try {
            windowManager.removeViewImmediate(panel.view)
        } catch (e: Exception) {
            // già rimosso
        }
    }

    /** Pannello scuro, oppure con la foto del tutore (banner grande o striscia sottile). */
    private fun buildView(heightPx: Int, label: String, layout: MaskLayout, photo: String?, caption: String): View {
        val store = photos
        if (layout == MaskLayout.DEFAULT_DARK || photo == null || store == null) {
            return MaskPanelViews.dark(service, heightPx, label)
        }
        return try {
            when (layout) {
                MaskLayout.FULL_BANNER -> MaskPanelViews.build(
                    service, layout, heightPx, label, caption, photo = store.bitmap(photo), blurred = store.blurred(photo)
                )
                else -> MaskPanelViews.build(
                    service, layout, heightPx, label, caption,
                    thumb = store.thumbnail(photo, (heightPx - (8 * density).toInt()).coerceAtLeast(16))
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foto non disponibile: ${e.javaClass.simpleName}")
            MaskPanelViews.dark(service, heightPx, label)
        }
    }

    companion object {
        private const val TAG = "CutBetAdMask"
        /** Margine di sicurezza intorno al box: aderente all'annuncio senza lasciarne vedere i bordi. */
        const val MARGIN_DP = 4f
        const val LABEL_AD = "Contenuto pubblicitario oscurato da CutBet"
        const val LABEL_CONTENT = "Contenuto oscurato da CutBet"
        const val LABEL_MAIL = "Email oscurata da CutBet"
    }
}
