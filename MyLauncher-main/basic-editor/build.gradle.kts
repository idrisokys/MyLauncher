import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")   // AGP 8.x: Kotlin eklentisi gerekli
}

android {
    namespace = "org.fossify.editor"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// androidphotofilters eski com.android.support paketini getirir; androidx ile çakıştığı için çıkarılır
configurations.all { exclude(group = "com.android.support") }

dependencies {
    // EditActivity extends commons' BaseSimpleActivity, so commons is part of the public API
    api("org.fossify:commons:7.1.3")

    implementation("com.vanniktech:android-image-cropper:4.7.0")             // crop / rotate / flip
    implementation("com.github.naveensingh:androidphotofilters:f2f3b444ea")  // filters (needs JitPack)
    implementation("com.github.bumptech.glide:glide:5.0.7")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime:2.10.0")
}
