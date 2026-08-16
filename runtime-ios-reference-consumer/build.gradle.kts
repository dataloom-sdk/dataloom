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
// tests already use. A second test in the same class proves a real
// DataLoom.synchronize() PULL pass genuinely writes an inbound change to
// the real SQLite database (summary.inboundEventsApplied == 1). This
// Windows development host can cross-compile iosSimulatorArm64Test/
// iosX64Test but cannot execute them -- only a real macOS host with the iOS
// Simulator can; see IosReferenceConsumerTest.kt's KDoc for the exact
// boundary this leaves open.
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

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
    ).forEach { target ->
        // SQLDelight's NativeSqliteDriver (used by SqlDelightStorageProvider,
        // reached transitively through dataloom-platform-ios ->
        // dataloom-storage-sqldelight) requires the system sqlite3 library.
        // dataloom-storage-sqldelight declares that dependency on its own
        // iosMain, so its own test/framework binaries link cleanly, but that
        // linker requirement does not reliably propagate across multiple
        // project-dependency hops in Kotlin/Native -- this module is three
        // hops downstream (runtime-ios-reference-consumer ->
        // dataloom-platform-ios -> dataloom-storage-sqldelight), and its
        // test binary genuinely failed to link with "Undefined symbols ...
        // _sqlite3_bind_blob" without this explicit flag (confirmed via a
        // real macOS CI failure, not guessed in advance). dataloom-apple's
        // XCFramework binaries.framework build does not need this same flag
        // -- Kotlin/Native's linker behaves differently for a
        // dynamic/static framework than for an executable test binary.
        target.binaries.all {
            linkerOpts += "-lsqlite3"
        }
    }

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
