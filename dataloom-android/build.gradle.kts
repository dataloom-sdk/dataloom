// DataLoom native Android platform artifact (#101 / DL-039A).
//
// Aggregates the connectivity/storage/queue/scheduler Android provider
// modules into one convenience dependency and supplies real, public
// production wiring helpers -- not a fixture, unlike
// runtime-android-reference-consumer.
//
// Rules:
// - May depend on the shared runtime and the four core Android provider
//   modules (connectivity, Room storage, Room queue, WorkManager scheduler).
// - Deliberately does not bundle a TransportProvider -- DataLoom never ships
//   a default transport; endpoint/auth/serialization stay application-owned.
// - Deliberately does not depend on dataloom-storage-datastore or
//   dataloom-storage-sqldelight-android -- those are alternative storage
//   choices an application opts into individually, not part of the "core
//   four" this module wires together. See docs/android/dataloom-android.md.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.dataloom.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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
    api(project(":dataloom-runtime"))
    api(project(":dataloom-connectivity-android"))
    api(project(":dataloom-storage-room"))
    api(project(":dataloom-queue-room"))
    api(project(":dataloom-scheduler-workmanager"))

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin)
}
