import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Firma di release: keystore.properties (LOCALE, escluso da git) nella radice del progetto, con
 * storeFile, storePassword, keyAlias, keyPassword. In alternativa le variabili d'ambiente
 * SAFEGUARD_STORE_FILE, SAFEGUARD_STORE_PASSWORD, SAFEGUARD_KEY_ALIAS, SAFEGUARD_KEY_PASSWORD
 * (es. in CI). Senza nessuna delle due la release viene prodotta non firmata.
 */
val releaseSigning: Map<String, String>? = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    fun value(key: String, env: String) = props.getProperty(key) ?: System.getenv(env)
    val storeFile = value("storeFile", "SAFEGUARD_STORE_FILE") ?: return@run null
    mapOf(
        "storeFile" to storeFile,
        "storePassword" to (value("storePassword", "SAFEGUARD_STORE_PASSWORD") ?: ""),
        "keyAlias" to (value("keyAlias", "SAFEGUARD_KEY_ALIAS") ?: "safeguard"),
        "keyPassword" to (value("keyPassword", "SAFEGUARD_KEY_PASSWORD") ?: ""),
    )
}

android {
    namespace = "com.zerobet.safeguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zerobet.safeguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.1"

        // URL dell'API dei bot Telegram. Solo per le prove con un server finto locale:
        // ./gradlew assembleRelease -PtelegramApiBase=http://127.0.0.1:8081 (abilita anche HTTP in chiaro).
        val telegramApiBase = (project.findProperty("telegramApiBase") as String?) ?: "https://api.telegram.org"
        buildConfigField("String", "TELEGRAM_API_BASE", "\"$telegramApiBase\"")
        manifestPlaceholders["cleartextTraffic"] = telegramApiBase.startsWith("http://").toString()
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = rootProject.file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager: aggiornamento quotidiano delle blocklist
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // Implementazione reale di org.json per i test JVM (su Android è quella della piattaforma).
    testImplementation("org.json:json:20240303")
}
