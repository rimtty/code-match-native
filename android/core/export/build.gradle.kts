plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "jp.rimtty.codematch.core.export"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:matching"))

    testImplementation(libs.junit)
    // Parses the generated history JSON back in unit tests; the exporter itself
    // stays dependency-free so it can run without Android's org.json stubs.
    testImplementation(libs.gson)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
