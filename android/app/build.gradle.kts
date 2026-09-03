plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Host-driven force-stop tests must never share the installed product's data.
// Both the application ID and test-only source are opt-in; ordinary debug,
// scannerPoc, release, and their existing test suites remain unchanged.
val processRecoveryTests = providers.gradleProperty("codematchProcessRecoveryTests")
    .map { it.toBooleanStrict() }
    .getOrElse(false)

android {
    namespace = "jp.rimtty.codematch"
    compileSdk = 37

    defaultConfig {
        applicationId = "jp.rimtty.codematch"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            if (processRecoveryTests) {
                applicationIdSuffix = ".recoverytest"
            }
        }
        create("scannerPoc") {
            isDebuggable = false
            // Resolve library release variants so debug-only Compose tooling
            // manifests cannot add externally exported activities to the PoC.
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".scannerpoc"
            versionNameSuffix = "-scanner-poc"
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "scanner-poc-rules.pro",
            )
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    if (processRecoveryTests) {
        sourceSets.getByName("androidTest").kotlin.directories.add("src/processRecoveryAndroidTest/java")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:matching"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(project(":core:export"))
    implementation(project(":feature:scan"))
    implementation(project(":feature:history"))
    implementation(project(":feature:settings"))
    implementation(project(":scanner:api"))
    implementation(project(":scanner:camera"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.dagger.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.adaptive)
    implementation(libs.androidx.compose.adaptive.navigation3)
    implementation(libs.androidx.compose.adaptive.navigation.suite)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    debugImplementation(project(":scanner:fake"))
    "scannerPocImplementation"(project(":scanner:inateck"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.datastore.preferences)

    ksp(libs.dagger.hilt.compiler)
}
