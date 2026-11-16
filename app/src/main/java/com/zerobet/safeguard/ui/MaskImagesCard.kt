package com.zerobet.safeguard.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zerobet.safeguard.SafeGuardApp
import com.zerobet.safeguard.guard.AdMaskOverlayManager
import com.zerobet.safeguard.mask.MaskImageFiles
import com.zerobet.safeguard.mask.MaskLayout
import com.zerobet.safeguard.mask.MaskPanelViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Immagini Pannello Oscurante" (dashboard, dietro PIN): il tutore sceglie fino a 10 foto e una
 * frase da mostrare al posto del pannello scuro quando CutBet oscura un annuncio o uno spam.
 */
@Composable
fun MaskImagesCard() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as SafeGuardApp).maskImages }
    val settings by store.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var caption by remember { mutableStateOf(settings.caption) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val remaining = MaskImageFiles.MAX_IMAGES - settings.images.size
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MaskImageFiles.MAX_IMAGES)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store.import(uris.take(store.remaining)) }
            busy = false
            message = when {
                saved == uris.size -> if (saved == 1) "1 foto aggiunta." else "$saved foto aggiunte."
                saved > 0 -> "$saved foto aggiunte (massimo ${MaskImageFiles.MAX_IMAGES})."
                else -> "Nessuna foto aggiunta."
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Immagini Pannello Oscurante", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Al posto del pannello scuro, sopra gli annunci e le email oscurate compaiono le foto che scegli " +
                    "(familiari, momenti felici) e, se vuoi, una frase. Le foto restano solo su questo telefono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Mostra foto personalizzate sui banner oscurati", Modifier.weight(1f).padding(end = 8.dp))
                Switch(checked = settings.enabled, onCheckedChange = { store.setEnabled(it) })
            }

            if (settings.images.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(settings.images, key = { it }) { name ->
                        Box(Modifier.size(84.dp)) {
                            val bitmap = remember(name) { store.thumbnail(name, 200) }
                            if (bitmap != null) {
                                Image(
                                    bitmap.asImageBitmap(), contentDescription = "Foto",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp))
                                )
                            }
                            Surface(
                                onClick = { store.remove(name) },
                                shape = CircleShape,
                                color = Color(0xCC000000),
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) { Text("✕", color = Color.White) }
                            }
                        }
                    }
                }
            }
            Text("${settings.images.size} di ${MaskImageFiles.MAX_IMAGES} foto", style = MaterialTheme.typography.bodySmall)

            OutlinedButton(
                onClick = {
                    MainActivity.markPickerOpening()
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = !busy && remaining > 0
            ) { Text(if (busy) "Copia in corso…" else "Aggiungi foto dalla Galleria") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            OutlinedTextField(
                value = caption,
                onValueChange = {
                    caption = it.take(com.zerobet.safeguard.mask.CustomMaskImageStore.MAX_CAPTION)
                    store.setCaption(caption)
                },
                label = { Text("Frase personalizzata (facoltativa)") },
                placeholder = { Text("es. Pensa a chi ti vuole bene") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Lasciala vuota per mostrare solo la foto; sulle strisce sottili comparirà la scritta standard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ---- Anteprima dal vivo: banner grande e striscia sottile ----
            Text("Anteprima", style = MaterialTheme.typography.labelLarge)
            val first = settings.images.firstOrNull().takeIf { settings.active }
            Text("Banner grande", style = MaterialTheme.typography.bodySmall)
            PanelPreview(store, first, MaskLayout.FULL_BANNER, 160, settings.caption)
            Text("Striscia sottile (es. riga di una email)", style = MaterialTheme.typography.bodySmall)
            PanelPreview(store, first, MaskLayout.COMPACT_STRIP, 64, settings.caption)
        }
    }
}

@Composable
private fun PanelPreview(
    store: com.zerobet.safeguard.mask.CustomMaskImageStore, photo: String?, layout: MaskLayout, heightDp: Int, caption: String,
) {
    val heightPx = with(LocalDensity.current) { heightDp.dp.roundToPx() }
    AndroidView(
        factory = { ctx -> android.widget.FrameLayout(ctx) },
        update = { frame ->
            frame.removeAllViews()
            val effective = if (photo == null) MaskLayout.DEFAULT_DARK else layout
            val view = MaskPanelViews.build(
                frame.context, effective, heightPx, AdMaskOverlayManager.LABEL_AD, caption,
                photo = photo?.let { store.bitmap(it) }, blurred = photo?.let { store.blurred(it) },
                thumb = photo?.let { store.thumbnail(it, heightPx) },
            )
            frame.addView(view, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, heightPx
            ))
        },
        modifier = Modifier.fillMaxWidth().height(heightDp.dp)
    )
}
