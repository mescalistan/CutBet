package com.zerobet.safeguard.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zerobet.safeguard.R

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

    private class Panel(val view: View, val params: WindowManager.LayoutParams, var box: Box)

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

    /** Riporta i pannelli esattamente sui [boxes] indicati, riusando le finestre esistenti. */
    fun show(packageName: String, boxes: List<Box>, label: String) {
        if (boxes.isEmpty()) {
            clear()
            return
        }
        ownerPackage = packageName
        // Stesse posizioni: nessun lavoro.
        if (boxes.size == panels.size && boxes.zip(panels).all { (b, p) -> b == p.box }) return
        while (panels.size > boxes.size) remove(panels.removeAt(panels.size - 1))
        boxes.forEachIndexed { i, box ->
            val panel = panels.getOrNull(i)
            if (panel == null) add(box, label) else move(panel, box)
        }
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

    fun clear() {
        panels.forEach { remove(it) }
        panels.clear()
        ownerPackage = null
    }

    private fun margin(): Int = (MARGIN_DP * density).toInt()

    private fun layoutFor(box: Box, params: WindowManager.LayoutParams) {
        val m = margin()
        params.x = box.left - m
        params.y = box.top - m
        params.width = box.width + 2 * m
        params.height = box.height + 2 * m
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun add(box: Box, label: String) {
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
        val view = buildView(box, label)
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
        try {
            windowManager.addView(view, params)
            panels += Panel(view, params, box)
        } catch (e: Exception) {
            Log.w(TAG, "Pannello non mostrato: ${e.javaClass.simpleName}")
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

    private fun buildView(box: Box, label: String): View {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, service.resources.displayMetrics).toInt() }
        val compact = box.height < dp(56f)
        return LinearLayout(service).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            background = GradientDrawable().apply {
                setColor(PANEL_COLOR)
                cornerRadius = dp(12f).toFloat()
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(ImageView(service).apply {
                setImageResource(R.drawable.ic_shield)
                setColorFilter(Color.argb(200, 255, 255, 255))
                val size = dp(if (compact) 16f else 24f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (compact) marginEnd = dp(6f) else bottomMargin = dp(4f)
                }
            })
            addView(TextView(service).apply {
                text = label
                setTextColor(Color.argb(210, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                maxLines = if (compact) 1 else 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
    }

    companion object {
        private const val TAG = "CutBetAdMask"
        private const val MARGIN_DP = 8f
        /** #1E222A pieno: con l'alfa F5 il testo dell'annuncio restava leggibile in trasparenza. */
        private val PANEL_COLOR = Color.parseColor("#FF1E222A")
        const val LABEL_AD = "Contenuto pubblicitario oscurato da CutBet"
        const val LABEL_CONTENT = "Contenuto oscurato da CutBet"
    }
}
