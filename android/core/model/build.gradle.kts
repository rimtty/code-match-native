plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "jp.rimtty.codematch.core.model"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}

// This module intentionally contains only framework-free Kotlin domain types.
// AGP's built-in Kotlin support compiles them while the Android library
// boundary keeps the module consumable by the Android application.
