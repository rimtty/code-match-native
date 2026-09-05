plugins { alias(libs.plugins.android.application) }

android {
    namespace = "jp.rimtty.codematch.sdkfaultprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "jp.rimtty.codematch.sdkfaultprobe"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { debug {
        isDebuggable = false
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    } }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,MAINIFEST.MF}"
}

// This is an opt-in diagnostic host, never a distributable release application.
androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {
    it.enable = false
}

dependencies {
    debugImplementation(project(":scanner:inateck"))
    // The SDK JAR has no transitive metadata for its ActivityCompat references.
    debugImplementation(libs.androidx.core.ktx)
}
