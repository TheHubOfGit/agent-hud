import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Read local bridge token & Mac LAN IP at build time so watch auto-pairs cleanly on first boot
val tokenFile = File(System.getProperty("user.home"), ".agenthud-bridge-token")
val defaultToken = if (tokenFile.exists()) tokenFile.readText().trim() else ""

android {
    namespace = "com.agenthud.watch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agenthud.watch"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "DEFAULT_AUTH_TOKEN", "\"$defaultToken\"")
        buildConfigField("int", "DEFAULT_PORT", "8765")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
