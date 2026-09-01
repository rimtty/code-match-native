plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "jp.rimtty.codematch.scanner.ble"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// This module contains the platform-neutral BLE protocol/state layer. An
// Android BluetoothGatt adapter can be supplied by the app later without
// leaking Android or vendor-SDK types into the scanner contract.
dependencies {
    implementation(project(":scanner:api"))
    implementation(libs.gson)

    testImplementation(libs.junit)
}
