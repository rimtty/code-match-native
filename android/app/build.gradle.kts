import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Host-driven force-stop tests must never share the installed product's data.
// Both the application ID and test-only source are opt-in; ordinary debug,
// release, and their existing test suites remain unchanged.
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

    // Release APKs are side-loaded for personal use only; there is no store
    // distribution. By default they are signed with the debug keystore so
    // reinstalls keep working across rebuilds. Supply a local keystore through
    // Gradle properties (for example in ~/.gradle/gradle.properties or
    // ORG_GRADLE_PROJECT_* environment variables) to sign with your own key:
    //   codematchReleaseStoreFile, codematchReleaseStorePassword,
    //   codematchReleaseKeyAlias, codematchReleaseKeyPassword
    val releaseStoreFile = providers.gradleProperty("codematchReleaseStoreFile").orNull
    if (!releaseStoreFile.isNullOrBlank()) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = providers.gradleProperty("codematchReleaseStorePassword").orNull
            keyAlias = providers.gradleProperty("codematchReleaseKeyAlias").orNull
            keyPassword = providers.gradleProperty("codematchReleaseKeyPassword").orNull
        }
    }

    buildTypes {
        debug {
            if (processRecoveryTests) {
                applicationIdSuffix = ".recoverytest"
            }
        }
        release {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            // Strip the Inateck SDK's raw-payload logging and keep JNA/native
            // entry points; see scanner-rules.pro.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "scanner-rules.pro",
            )
            // The official SDK ships arm64-v8a native libraries only.
            ndk {
                abiFilters += "arm64-v8a"
            }
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

    // The app changes its locale locally and must carry both supported
    // languages in every base APK. Keep the default ABI and density splits.
    bundle {
        language {
            enableSplit = false
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    if (processRecoveryTests) {
        sourceSets.getByName("debug").manifest.srcFile("src/processRecovery/AndroidManifest.xml")
        sourceSets.getByName("androidTest").kotlin.directories.add("src/processRecoveryAndroidTest/java")
    }
}

// Verify the published AAB rather than only trusting the Gradle DSL. AGP's
// bundletool and aapt2-proto classes are already available through the Android
// plugin classloader; the verifier task resolves their code-source locations
// lazily so ordinary app tasks do not depend on Gradle cache paths or new
// dependency coordinates.
val bundleLanguageVerifierRuntimeClasspath = providers.provider {
    val classLoaders = listOf(
        android.javaClass.classLoader,
        Thread.currentThread().contextClassLoader,
        ClassLoader.getSystemClassLoader(),
    ).filterNotNull().distinct()

    fun codeSourceFor(className: String): File {
        val loadedClass = classLoaders.asSequence()
            .mapNotNull { loader ->
                runCatching { Class.forName(className, false, loader) }.getOrNull()
            }
            .firstOrNull()
            ?: throw GradleException("Unable to load $className from the AGP plugin classpath")
        val location = loadedClass.protectionDomain?.codeSource?.location
            ?: throw GradleException("No code source for $className")
        return File(location.toURI())
    }

    files(
        codeSourceFor("com.android.bundle.Config"),
        codeSourceFor("com.android.aapt.Resources"),
        codeSourceFor("com.google.protobuf.Message"),
    )
}

val bundleLanguageVerifierClasses = layout.buildDirectory.dir(
    "generated/bundle-language-verifier/classes",
)
val bundleLanguageVerifierCompile = tasks.register<JavaCompile>(
    "compileBundleLanguageVerifier",
) {
    group = "verification"
    description = "Compiles the typed AAB language-delivery verifier."
    source(
        rootProject.file("scripts/BundleLanguageVerifier.java"),
        rootProject.file("scripts/BundleLanguageVerifierTest.java"),
    )
    destinationDirectory.set(bundleLanguageVerifierClasses)
    classpath = files(bundleLanguageVerifierRuntimeClasspath)
    options.release.set(17)
    options.encoding = "UTF-8"
}

val bundleLanguageVerifierClasspath = files(
    bundleLanguageVerifierClasses,
    bundleLanguageVerifierRuntimeClasspath,
)

tasks.register<JavaExec>("testBundleLanguageVerifier") {
    group = "verification"
    description = "Runs positive and negative typed AAB verifier fixtures."
    dependsOn(bundleLanguageVerifierCompile)
    classpath = bundleLanguageVerifierClasspath
    mainClass.set("BundleLanguageVerifierTest")
}

tasks.register<JavaExec>("verifyReleaseLanguageDelivery") {
    group = "verification"
    description = "Verifies the release AAB language split and resources."
    dependsOn(bundleLanguageVerifierCompile, "bundleRelease")
    classpath = bundleLanguageVerifierClasspath
    mainClass.set("BundleLanguageVerifier")
    val releaseBundle = layout.buildDirectory.file("outputs/bundle/release/app-release.aab")
    inputs.file(releaseBundle)
    args(releaseBundle.get().asFile.absolutePath)
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
    releaseImplementation(project(":scanner:inateck"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.datastore.preferences)

    ksp(libs.dagger.hilt.compiler)
}
