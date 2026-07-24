plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screenpilot.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screenpilot.player"
        minSdk = 21          // old Android TV boxes still in the field
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Plain Activity + WebView on purpose: no androidx dependencies keeps the
// APK tiny (~1 MB) and compatible with low-end TV boxes.
dependencies {
}
