plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "jp.rimtty.codematch.scanner.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The API deliberately has no Android, camera, Bluetooth, or coroutine
// dependency. Platform adapters communicate through the synchronous listener
// contract and may add their own asynchronous implementation details later.
