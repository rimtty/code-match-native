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
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
