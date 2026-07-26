// DataLoom WorkManager scheduler provider.
//
// Provides WorkManagerSchedulerProvider — an AndroidX WorkManager-backed
// SchedulerProvider — and DataLoomCoroutineWorker with DataLoomWorkerFactory
// for explicit Worker injection.
//
// Rules:
// - May depend on dataloom-api, dataloom-runtime, and AndroidX WorkManager.
// - Must not depend on Room, dataloom-queue-room, or dataloom-connectivity-android.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.dataloom.scheduler.workmanager"
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
    // DataLoom public API contracts and runtime queue-worker contracts
    implementation(project(":dataloom-api"))
    implementation(project(":dataloom-runtime"))

    // AndroidX WorkManager with coroutines support
    implementation(libs.workmanager.ktx)

    // Kotlin coroutines for Android
    implementation(libs.kotlinx.coroutines.android)

    // Local JVM unit tests
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.mockito.kotlin)
}
