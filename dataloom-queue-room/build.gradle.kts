// DataLoom Room queue provider.
//
// Provides RoomQueueProvider — an AndroidX Room-backed QueueProvider with
// transactional bounded acquisition, guarded lease-aware transitions, and
// transactional expired-lease recovery.
//
// Rules:
// - May depend on dataloom-api, Room, and SQLite APIs required by Room.
// - Must not depend on WorkManager, dataloom-scheduler-workmanager, or
//   dataloom-connectivity-android.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.dataloom.queue.room"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
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

// Export Room schemas for migration testing.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // DataLoom public queue provider API contracts
    implementation(project(":dataloom-api"))

    // Room runtime and KTX extensions
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // Room annotation processor via KSP
    ksp(libs.room.compiler)

    // Local JVM unit tests
    testImplementation(kotlin("test"))
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.room.testing)
}
