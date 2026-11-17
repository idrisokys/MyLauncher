import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// İmza bilgileri proje kökündeki keystore.properties dosyasından okunur (git'e EKLEME).
// Dosya yoksa release APK debug anahtarıyla imzalanır.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "github.idrisokys.myl"
    compileSdk = 36

    defaultConfig {
        applicationId = "github.idrisokys.myl"
        minSdk = 29
        targetSdk = 34
        versionCode = 17
        versionName = "1.14"
    }

    androidResources { localeFilters += listOf("tr", "en") }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: kodu küçültür/optimize eder, APK daha küçük ve daha hızlı açılır
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Notlarım ekranı (eski LinkCepte) Jetpack Compose ile yazılmıştır (Kotlin 2.x: Compose derleyicisi eklenti olarak gelir)
    buildFeatures { compose = true }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

// androidphotofilters (editör filtreleri) eski com.android.support:support-compat:25.3.0'ı getiriyor;
// androidx.core ile aynı sınıfları içerdiği için "Duplicate class" hatası verir. Eski paketi çıkarıyoruz.
configurations.all { exclude(group = "com.android.support") }

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Galerim'deki sihirli değnek: Fossify temel resim düzenleyicisi (filtre, kırpma, döndürme, çizim)
    implementation(project(":basic-editor"))
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.core:core:1.16.0")
    // Galerim: tam ekran resim/video gezgini (ViewPager)
    implementation("androidx.viewpager:viewpager:1.0.0")
    // Notlarım (Compose)
    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
}
