// DataLoom connectivity provider for Android.
//
// Provides AndroidConnectivityProvider — a single bounded query of the
// Android ConnectivityManager for the current device-level network state.
//
// Rules:
// - May depend on dataloom-api and Android framework connectivity APIs.
// - Must not depend on Room, SQLite, WorkManager, or other DataLoom Android modules.
plugins {
    alias(libs.plugins.android.library)
}

private const val DEFAULT_COMPILE_SDK = 35
private const val DEFAULT_MIN_SDK = 21

android {
    namespace = "io.dataloom.connectivity.android"
    // Centralized compileSdk from version catalog
    compileSdk = libs.versions.android.compileSdk.get().toIntOrNull() ?: DEFAULT_COMPILE_SDK

    defaultConfig {
        // Centralized minSdk from version catalog
        minSdk = libs.versions.android.minSdk.get().toIntOrNull() ?: DEFAULT_MIN_SDK
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // DataLoom public API contracts
    implementation(project(":dataloom-api"))

    // Local JVM unit tests
    testImplementation(kotlin("test"))
    testImplementation(libs.mockito.kotlin)
}
