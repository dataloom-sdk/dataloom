// DataLoom DataStore storage provider.
//
// Provides DataStoreStorageProvider — a Jetpack Preferences DataStore-backed
// StorageProvider targeted at small, bounded key-value synchronization state
// such as user settings, feature flags, and small cached preferences.
//
// This module is Android-only. Jetpack DataStore does not support
// Kotlin Multiplatform. It is deliberately opt-in and must not become a
// mandatory dependency of any shared DataLoom module.
//
// Use dataloom-queue-room for general-purpose, large-scale, or relationally
// structured synchronization data. See issue #209 and #215.
//
// Rules:
// - May depend on dataloom-api and androidx.datastore:datastore-preferences.
// - Must not depend on Room, WorkManager, or other DataLoom Android modules.
// - Must not become a transitive dependency of dataloom-core or dataloom-runtime.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.dataloom.storage.datastore"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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
    api(project(":dataloom-api"))
    api(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin)
}
