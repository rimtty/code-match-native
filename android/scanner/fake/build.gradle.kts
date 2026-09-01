plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "jp.rimtty.codematch.scanner.fake"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Fake scanner implementations belong here during M2 development. The app
// consumes this module through debugImplementation only; no release variant
// may depend on it.
