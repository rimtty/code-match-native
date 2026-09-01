plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "jp.rimtty.codematch.core.export"
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
    implementation(project(":core:model"))
    implementation(project(":core:matching"))

    testImplementation(libs.junit)
}
