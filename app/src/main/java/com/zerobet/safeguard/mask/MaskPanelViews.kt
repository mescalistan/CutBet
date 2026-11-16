package com.zerobet.safeguard.mask

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zerobet.safeguard.R

/**
 * Viste dei pannelli oscuranti, usate sia dai pannelli veri (AdMaskOverlayManager) sia
 * dall'anteprima nella dashboard. Sempre opache al 100%: l'annuncio sotto non si vede mai.
 */
object MaskPanelViews {

    private val PANEL_COLOR = Color.parseColor("#FF1E222A")
    private val SHADE = Color.argb(180, 15, 18, 24)
    private val CAPTION_BAND = Color.argb(170, 0, 0, 0)

    private fun dp(context: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    private fun rounded(view: View, radiusPx: Float) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
        }
        view.clipToOutline = true
    }

    /**
     * @param heightPx altezza del pannello (per la miniatura della striscia)
     * @param photo foto intera (FULL_BANNER), [blurred] versione minuscola per lo sfondo
     * @param thumb miniatura quadrata (COMPACT_STRIP)
     * @param caption frase del tutore (vuota = nessuna didascalia sul banner, scritta standard sulla striscia)
     */
    fun build(
        context: Context,
        layout: MaskLayout,
        heightPx: Int,
        defaultLabel: String,
        caption: String,
        photo: Bitmap? = null,
        blurred: Bitmap? = null,
        thumb: Bitmap? = null,
    ): View = when {
        layout == MaskLayout.FULL_BANNER && photo != null -> banner(context, photo, blurred, caption)
        layout == MaskLayout.COMPACT_STRIP && thumb != null -> strip(context, heightPx, thumb, caption.ifBlank { defaultLabel })
        else -> dark(context, heightPx, defaultLabel)
    }.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** Pannello scuro con scudo e scritta (comportamento della 1.4.x). */
    fun dark(context: Context, heightPx: Int, label: String): View {
        val compact = heightPx < dp(context, 56f)
        return LinearLayout(context).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 8f), dp(context, 4f), dp(context, 8f), dp(context, 4f))
            background = GradientDrawable().apply {
                setColor(PANEL_COLOR)
                cornerRadius = dp(context, 12f).toFloat()
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_shield)
                setColorFilter(Color.argb(200, 255, 255, 255))
                val size = dp(context, if (compact) 16f else 24f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (compact) marginEnd = dp(context, 6f) else bottomMargin = dp(context, 4f)
                }
            })
            addView(text(context, label, 12f, if (compact) 1 else 2))
        }
    }

    /** Banner grande: sfondo sfumato e scurito della foto, foto intera nitida al centro, didascalia. */
    private fun banner(context: Context, photo: Bitmap, blurred: Bitmap?, caption: String): View =
        FrameLayout(context).apply {
            setBackgroundColor(PANEL_COLOR)
            rounded(this, dp(context, 12f).toFloat())
            if (blurred != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(blurred)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setColorFilter(SHADE)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            // Foto intera: mai deformata (FIT_CENTER), le persone restano interamente visibili.
            addView(ImageView(context).apply {
                setImageBitmap(photo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val p = dp(context, 6f)
                setPadding(p, p, p, p)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (caption.isNotBlank()) {
                addView(text(context, caption, 14f, 2).apply {
                    setBackgroundColor(CAPTION_BAND)
                    setPadding(dp(context, 10f), dp(context, 6f), dp(context, 10f), dp(context, 6f))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            }
        }

    /** Striscia sottile: miniatura quadrata arrotondata a sinistra, frase a destra. */
    private fun strip(context: Context, heightPx: Int, thumb: Bitmap, label: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(context, 4f)
            setPadding(pad, pad, dp(context, 10f), pad)
            background = GradientDrawable().apply {
                setColor(PANEL_COLOR)
                cornerRadius = dp(context, 10f).toFloat()
            }
            val side = (heightPx - 2 * pad).coerceAtLeast(dp(context, 16f))
            addView(ImageView(context).apply {
                setImageBitmap(thumb)
                scaleType = ImageView.ScaleType.CENTER_CROP
                rounded(this, dp(context, 8f).toFloat())
            }, LinearLayout.LayoutParams(side, side).apply { marginEnd = dp(context, 10f) })
            addView(text(context, label, 13f, if (heightPx < dp(context, 48f)) 1 else 2).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun text(context: Context, value: String, sp: Float, lines: Int) = TextView(context).apply {
        text = value
        setTextColor(Color.argb(225, 255, 255, 255))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        gravity = Gravity.CENTER
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}
