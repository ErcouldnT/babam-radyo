import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Imzalama bilgileri depoya girmez. Kok dizindeki keystore.properties
// (ve isaret ettigi .keystore dosyasi) .gitignore icindedir; bunlar yoksa
// release derlemesi imzasiz uretilir.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = keystoreProps.getProperty("storeFile")
    ?.let { project.file(it).exists() } == true

// Backend adresi ve gizli anahtar da depoya girmez; api.properties
// .gitignore icindedir. Dosya yoksa uygulama YouTube araması olmadan
// derlenir - radyo, arşiv ve indirilenler etkilenmez.
val apiProps = Properties().apply {
    val f = rootProject.file("api.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * Anahtari APK icinde duz metin olarak birakmamak icin basit bir XOR.
 * Bu, `strings` ile bakan birine karsi ise yarar; kararli bir tersine
 * muhendise karsi yaramaz - APK'ya sahip olan anahtari cikarabilir.
 * Gercek koruma anahtarin gerektiginde degistirilebilmesidir.
 */
val obfuscationKey = "BabamRadyo-2026"

fun obfuscate(value: String): String =
    value.toByteArray(Charsets.UTF_8)
        .mapIndexed { i, b -> (b.toInt() xor obfuscationKey[i % obfuscationKey.length].code) }
        .joinToString("") { "%02x".format(it and 0xFF) }

android {
    namespace = "dev.erkut.babamradyo"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.erkut.babamradyo"
        minSdk = 26          // Android 8.0 - babanın Oppo'su Android 10 (API 29)
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"

        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${apiProps.getProperty("API_BASE_URL", "").trimEnd('/')}\""
        )
        buildConfigField(
            "String",
            "API_TOKEN_OBFUSCATED",
            "\"${obfuscate(apiProps.getProperty("API_TOKEN", ""))}\""
        )
        buildConfigField("String", "API_OBFUSCATION_KEY", "\"$obfuscationKey\"")
        resourceConfigurations += listOf("tr", "en")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Sadece oynatma altyapısı. Hicbir YouTube / 3. parti servis SDK'si yok.
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
}
