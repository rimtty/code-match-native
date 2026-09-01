plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "jp.rimtty.codematch.core.matching"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // Keep the platform-neutral fixtures on the test runtime classpath.
        // Tests should load them through the classloader (for example,
        // getResourceAsStream("matching-cases.json")), never by assuming the
        // repository working directory.
        getByName("test") {
            resources.srcDir(rootProject.layout.projectDirectory.dir("../shared/test-fixtures"))
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(libs.junit)
    testImplementation(libs.gson)
}

// Keep parser/matcher sources independent of Android APIs so JVM-style tests
// can exercise the same contract as the iOS implementation.
