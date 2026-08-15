// iOS reference consumer for #101 (DL-039A).
//
// Mirrors runtime-android-reference-consumer's own documented scope
// (proving dependency-graph resolution, public-API wiring, and -- now --
// runtime behavior), applied to dataloom-platform-ios's real production
// wiring helper instead of dataloom-android's.
//
// Proves that dataloom-platform-ios's appleDataLoomProviders/
// installAppleProviders helpers (which wire AppleConnectivityProvider,
// SqlDelightStorageProvider, AppleFileQueueProvider, and
// AppleSchedulerProvider) actually compose with DataLoomBuilder into one
// buildable DataLoom instance -- dogfooding dataloom-platform-ios's own
// public API rather than hand-wiring the four providers directly.
//
// IosReferenceConsumerTest (src/iosTest) additionally proves those four
// real providers construct and initialize()/shut down cleanly against a
// real Kotlin/Native iOS Simulator runtime, executed by
// iosSimulatorArm64Test/iosX64Test on macOS CI -- the same real-runtime
// execution mechanism (not a JVM shadow layer like Robolectric)
// dataloom-runtime's own Apple circuit/queue/retry-administration store
// tests already use. This Windows development host can cross-compile
// iosSimulatorArm64Test/iosX64Test but cannot execute them -- only a real
// macOS host with the iOS Simulator can; see IosReferenceConsumerTest.kt's
// KDoc for the exact boundary this leaves open.
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
        // named("iosMain")/named("iosTest") are synchronous, point-in-time
        // lookups and return null here: Kotlin does not register the
        // "iosMain"/"iosTest" intermediate source sets until later in
        // their own target-configuration lifecycle. matching(...).
        // configureEach is the lazy-provider workaround
        // dataloom-storage-sqldelight's build.gradle.kts already documents
        // and uses for the identical reason.
        matching { it.name == "iosMain" }.configureEach {
            dependencies {
                implementation(project(":dataloom-platform-ios"))
            }
        }
        matching { it.name == "iosTest" }.configureEach {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
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
