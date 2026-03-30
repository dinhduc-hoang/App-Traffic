plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

import java.util.Properties

android {
    namespace = "com.example.utt_trafficjams"
    compileSdk = 35

    val localProps = Properties().apply {
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { load(it) }
        }
    }

    defaultConfig {
        applicationId = "com.example.utt_trafficjams"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Đọc Gemini API Key từ local.properties
        val geminiKey = (
            localProps.getProperty("GEMINI_API_KEY")
                ?: System.getenv("GEMINI_API_KEY")
                ?: ""
            ).trim()
        val escapedGeminiKey = geminiKey
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$escapedGeminiKey\"")

        // Đọc Google Maps API Key từ local.properties
        val mapsKey = (
            localProps.getProperty("MAPS_API_KEY")
                ?: localProps.getProperty("GOOGLE_MAPS_API_KEY")
                ?: System.getenv("MAPS_API_KEY")
                ?: System.getenv("GOOGLE_MAPS_API_KEY")
                ?: ""
            ).trim()
        val escapedMapsKey = mapsKey
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "MAPS_API_KEY", "\"$escapedMapsKey\"")
        manifestPlaceholders["MAPS_API_KEY"] = mapsKey
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    debugImplementation(libs.compose.ui.tooling)

    // ViewModel + Compose
    implementation(libs.lifecycle.viewmodel.compose)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    // OkHttp — Gemini Live API WebSocket
    implementation(libs.okhttp)

    // Google Maps
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.google.maps.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}