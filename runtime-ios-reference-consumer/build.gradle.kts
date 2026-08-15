// iOS reference consumer for #101 (DL-039A).
//
// Compile-only fixture -- mirrors runtime-android-reference-consumer's own
// documented scope (proving dependency-graph resolution and public-API
// wiring compile correctly), applied to dataloom-platform-ios's real
// production wiring helper instead of dataloom-android's.
//
// Proves that dataloom-platform-ios's appleDataLoomProviders/
// installAppleProviders helpers (which wire AppleConnectivityProvider,
// SqlDelightStorageProvider, AppleFileQueueProvider, and
// AppleSchedulerProvider) actually compose with DataLoomBuilder into one
// buildable DataLoom instance -- dogfooding dataloom-platform-ios's own
// public API rather than hand-wiring the four providers directly. Does not
// prove runtime behavior on a device or simulator; see
// IosReferenceConsumer.kt's KDoc for the explicit boundary.
//
// Rules:
// - May depend on dataloom-platform-ios (which itself depends on
//   dataloom-api, dataloom-runtime, and dataloom-storage-sqldelight).
// - Must not depend on dataloom-core directly, same public-surface-only rule
//   runtime-android-reference-consumer already enforces for the Android path.
// - Consuming code lives in src/iosMain, matching AppleDataLoomProviders'
//   own placement: appleDataLoomProviders()/installAppleProviders() are
//   iosMain-only declarations, not part of dataloom-platform-ios's common
//   API surface.
// - Included in the Gradle build only under the same isAppleHost ||
//   dataloom.appleKlibCrossCompile condition as dataloom-platform-ios (see
//   settings.gradle.kts) -- no gating mismatch.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    explicitApi()

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        // named("iosMain") is a synchronous, point-in-time lookup and
        // returns null here: Kotlin does not register the "iosMain"
        // intermediate source set until later in its own
        // target-configuration lifecycle. matching(...).configureEach is
        // the lazy-provider workaround dataloom-storage-sqldelight's
        // build.gradle.kts already documents and uses for the identical
        // reason.
        matching { it.name == "iosMain" }.configureEach {
            dependencies {
                implementation(project(":dataloom-platform-ios"))
            }
        }
    }
}

tasks.register("checkRuntimeIosReferenceConsumer") {
    group = "verification"
    description = "Cross-compiles the iOS reference consumer fixture."
    dependsOn(tasks.named("compileKotlinIosArm64"))
    dependsOn(tasks.named("compileKotlinIosSimulatorArm64"))
    dependsOn(tasks.named("compileKotlinIosX64"))
}

tasks.named("check") {
    dependsOn("checkRuntimeIosReferenceConsumer")
}
