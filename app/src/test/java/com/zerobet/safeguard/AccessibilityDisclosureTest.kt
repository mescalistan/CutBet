package com.zerobet.safeguard

import com.zerobet.safeguard.guard.GuardState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccessibilityDisclosureTest {

    private val own = "com.zerobet.safeguard"
    private val settings = setOf("com.android.settings", "com.android.vpndialogs", "com.google.android.packageinstaller")
    private val ignored = setOf("com.google.android.apps.nexuslauncher", "com.android.systemui", "com.google.android.inputmethod.latin")

    @Test
    fun `la grazia per le Impostazioni finisce solo uscendo verso un'altra app`() {
        // Restare nelle Impostazioni, nei dialoghi di sistema, in CutBet, nella Home o nella tastiera: grazia intatta.
        listOf("com.android.settings", "com.android.vpndialogs", own, "com.google.android.apps.nexuslauncher",
            "com.android.systemui", "com.google.android.inputmethod.latin")
            .forEach { assertFalse(it, GuardState.leavesSettings(it, own, settings, ignored)) }
        // Aprire il browser o un'altra app: la protezione riparte subito.
        listOf("com.android.chrome", "com.google.android.youtube", "it.subito")
            .forEach { assertTrue(it, GuardState.leavesSettings(it, own, settings, ignored)) }
    }

    @Test
    fun `descrizione del servizio di accessibilita aggiornata e veritiera`() {
        val strings = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.exists() }.readText()
        val description = Regex("<string name=\"accessibility_service_description\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(strings)!!.groupValues[1]
        // Dalla 1.4.3 la posta viene letta (righe di spam oscurate): la vecchia frase era falsa.
        assertFalse(description.contains("posta non vengono lette"))
        assertTrue(description.contains("posta") && description.contains("singole email"))
        assertTrue(description.contains("Non legge SMS, WhatsApp, Signal, telefono, rubrica"))
        assertTrue(description.contains("SPID, CIE e Pubblica Amministrazione"))
        assertTrue(description.contains("Bot Telegram") && description.contains("breve estratto"))
        // Apostrofi sempre preceduti da \ (altrimenti aapt rifiuta la risorsa).
        assertFalse(Regex("(?<!\\\\)'").containsMatchIn(description))
    }
}
