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
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.dataloom.connectivity.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
