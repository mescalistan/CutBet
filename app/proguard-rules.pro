# Tink (usato da security-crypto) referenzia annotazioni opzionali non presenti a runtime.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# ---------------------------------------------------------------------------------------------
# Componenti istanziati dal sistema per nome (manifest): AAPT genera già le regole, queste sono
# una garanzia esplicita anche per i costruttori usati via riflessione.
# ---------------------------------------------------------------------------------------------
-keep class * extends android.content.BroadcastReceiver { <init>(); }
-keep class * extends android.app.Service { <init>(); }
-keep class * extends android.accessibilityservice.AccessibilityService { <init>(); }
-keep class * extends android.app.admin.DeviceAdminReceiver { <init>(); }
-keep class * extends android.app.Activity { <init>(); }

# WorkManager crea i worker per nome di classe con (Context, WorkerParameters).
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room: implementazioni generate da KSP caricate per nome (AppDatabase_Impl, *Dao_Impl) ed entità.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.paging.**

# org.json (risposte di Telegram e crt.sh) è parte della piattaforma Android: niente da tenere,
# ma le classi del modello di SafeGuard lette per campo restano integre.
-keep class com.zerobet.safeguard.data.** { *; }

# Messaggi di log: R8 rimuove le chiamate di debug/verbose della release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
