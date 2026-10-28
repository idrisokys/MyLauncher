plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
    buildTypes {
        release {
            // R8: kodu küçültür/optimize eder, APK daha küçük ve daha hızlı açılır
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // İmzasız release APK yüklenemez; kendi keystore'unu ekleyene kadar debug anahtarıyla imzala
            signingConfig = signingConfigs.getByName("debug")
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
