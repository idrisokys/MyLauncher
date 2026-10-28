import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// İmza bilgileri proje kökündeki keystore.properties dosyasından okunur (git'e EKLEME).
// Dosya yoksa release APK debug anahtarıyla imzalanır.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.example.myl"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myl"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

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
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
