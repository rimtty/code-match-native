plugins { alias(libs.plugins.android.application) }

android {
    namespace = "jp.rimtty.codematch.sdkprobe"
    compileSdk = 37
    defaultConfig {
        applicationId = "jp.rimtty.codematch.sdkprobe"
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
    // Native/JAR artifacts stay in the existing ignored, locally bootstrapped location.
    sourceSets["main"].jniLibs.srcDir("../../scanner/inateck/src/main/jniLibs")
    buildTypes {
        debug {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,MAINIFEST.MF}"
}

dependencies {
    // The vendor JAR references ActivityCompat but ships no dependency metadata.
    implementation(libs.androidx.core.ktx)
    implementation(libs.fastble)
    implementation(libs.gson)
    implementation(files("../../scanner/inateck/libs/inateck-scanner-ble-2-0-0.jar"))
    implementation(files("../../scanner/inateck/libs/jna-min.jar"))
    testImplementation(libs.junit)
}
