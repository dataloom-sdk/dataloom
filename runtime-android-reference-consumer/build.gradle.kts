// Native Android reference consumer for #101 (DL-039A).
//
// Mirrors runtime-external-consumer's own documented scope (proving
// dependency-graph resolution and public-API wiring compile correctly),
// applied to dataloom-android's real production wiring helper instead of
// the JVM-only public runtime surface.
//
// Proves that dataloom-android's installAndroidProviders/androidDataLoomProviders
// helpers (which wire AndroidConnectivityProvider, RoomStorageProvider,
// RoomQueueProvider, and WorkManagerSchedulerProvider) actually compose with
// DataLoomBuilder into one buildable DataLoom instance -- dogfooding
// dataloom-android's own public API rather than hand-wiring the four
// providers directly.
//
// A Robolectric-backed unit test (AndroidReferenceConsumerRobolectricTest)
// now additionally proves those four real providers actually construct and
// initialize/shut down cleanly against a real (simulated) Android runtime --
// a genuine Room database open, a real WorkManager instance, a real
// ConnectivityManager service lookup -- not just compile. A second test in
// the same class proves a real DataLoom.synchronize() PULL pass genuinely
// writes an inbound change to that real Room database
// (summary.inboundEventsApplied == 1). It does not prove behavior on a
// physical device or emulator, and it does not exercise the full
// foreground/offline/retry/conflict/asset/cancellation matrix #101's
// acceptance criteria require; see AndroidReferenceConsumer.kt's and the
// Robolectric test's own KDoc for the exact boundary.
//
// Rules:
// - May depend on dataloom-android (which itself depends on the shared
//   runtime and the four Android provider modules).
// - Must not depend on dataloom-core directly (same public-surface-only rule
//   runtime-external-consumer already enforces for the JVM path).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.dataloom.consumer.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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
    implementation(project(":dataloom-model"))
    implementation(project(":dataloom-provider-api"))
    implementation(project(":dataloom-api"))
    implementation(project(":dataloom-android"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.workmanager.testing)
}

tasks.register("checkRuntimeAndroidReferenceConsumer") {
    group = "verification"
    description = "Compiles the native Android reference consumer fixture."
    dependsOn(tasks.named("compileDebugKotlin"))
}

tasks.named("check") {
    dependsOn("checkRuntimeAndroidReferenceConsumer")
}
