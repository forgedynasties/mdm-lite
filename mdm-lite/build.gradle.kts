// AIO MDM-lite: an embeddable library that gives any app on any Android device the
// MDM's vitals and crash reporting with no Device Owner, no adb and no root. It speaks
// the same device protocol as the DPC agent (enroll, check-in, crash_events) and has
// no dependencies beyond the Android SDK, so it cannot clash with the host app's own
// HTTP or JSON stack.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aioapp.mdmlite"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "LIB_VERSION", "\"0.1.8\"")
    }
    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
