plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "jp.rimtty.codematch.scanner.inateck"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,MAINIFEST.MF}"
        }
    }
}

val inateckSdkArtifacts = files(
    "libs/inateck-scanner-ble-2-0-0.jar",
    "libs/jna-min.jar",
    "src/main/jniLibs/arm64-v8a/libjnidispatch.so",
    "src/main/jniLibs/arm64-v8a/libscanner_cmd.so",
    "src/main/jniLibs/arm64-v8a/libinateck_scanner_cmd.so",
)

val verifyInateckSdkArtifacts by tasks.registering {
    inputs.files(inateckSdkArtifacts)
    doLast {
        val missing = inputs.files.files.filterNot { it.isFile }
        check(missing.isEmpty()) {
            "Inateck SDK PoC files are missing. Run android/scripts/setup-inateck-sdk-poc.sh"
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyInateckSdkArtifacts)
}

dependencies {
    implementation(project(":scanner:api"))
    implementation(project(":scanner:ble"))
    implementation(libs.fastble)
    implementation(libs.gson)
    implementation(libs.androidx.lifecycle.process)
    implementation(files("libs/inateck-scanner-ble-2-0-0.jar"))
    implementation(files("libs/jna-min.jar"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.core.ktx)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
