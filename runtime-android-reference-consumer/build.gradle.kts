// Native Android reference consumer for #101 (DL-039A).
//
// Compile-only fixture — mirrors runtime-external-consumer's own documented
// scope (proving dependency-graph resolution and public-API wiring compile
// correctly), applied to the four real Android provider modules instead of
// the JVM-only public runtime surface.
//
// Proves that AndroidConnectivityProvider, RoomStorageProvider,
// RoomQueueProvider, and WorkManagerSchedulerProvider actually compose with
// DataLoomBuilder into one buildable DataLoom instance -- something no
// existing module or test exercised together before this one. Does not
// prove runtime behavior on a device or emulator; see
// AndroidReferenceConsumer.kt's KDoc for the explicit boundary.
//
// Rules:
// - May depend on the shared runtime and the four Android provider modules.
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
}

dependencies {
    implementation(project(":dataloom-model"))
    implementation(project(":dataloom-provider-api"))
    implementation(project(":dataloom-api"))
    implementation(project(":dataloom-runtime"))
    implementation(project(":dataloom-connectivity-android"))
    implementation(project(":dataloom-storage-room"))
    implementation(project(":dataloom-queue-room"))
    implementation(project(":dataloom-scheduler-workmanager"))
    implementation(libs.kotlinx.coroutines.core)
}

tasks.register("checkRuntimeAndroidReferenceConsumer") {
    group = "verification"
    description = "Compiles the native Android reference consumer fixture."
    dependsOn(tasks.named("compileDebugKotlin"))
}

tasks.named("check") {
    dependsOn("checkRuntimeAndroidReferenceConsumer")
}
