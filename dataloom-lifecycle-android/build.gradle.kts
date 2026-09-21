// DataLoom lifecycle provider for Android.
//
// Provides AndroidLifecycleProvider -- an AppLifecycleProvider backed by the
// AndroidX process lifecycle (ProcessLifecycleOwner), exposing the app's
// coarse foreground/background state as a cold, cancellation-safe Flow.
// See docs/api/app-lifecycle-provider.md and ADR-0007.
//
// Rules:
// - May depend on dataloom-api, AndroidX lifecycle-process, and
//   kotlinx-coroutines-android (main-thread observer registration).
// - Must not depend on Room, SQLite, WorkManager, or other DataLoom Android modules.
// - dataloom-testing is a test-only dependency (shared contract suite and fake).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.dataloom.lifecycle.android"
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
    // DataLoom public API contracts (AppLifecycleProvider, AppLifecycleState).
    api(project(":dataloom-api"))

    // Process-wide lifecycle. Its manifest registers ProcessLifecycleInitializer
    // through androidx.startup, which is what starts the process lifecycle.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)

    // Local JVM unit tests (Robolectric: real Looper and main dispatcher)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(project(":dataloom-testing"))
}
