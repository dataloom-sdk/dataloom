// DataLoom platform-ios module (#101 / DL-039A).
//
// Third bounded slice of the eventual dataloom-ios platform artifact: real
// ConnectivityProvider and SchedulerProvider implementations for iOS, plus
// AppleDataLoomProviders -- an aggregation of those two providers with the
// existing SqlDelightStorageProvider (dataloom-storage-sqldelight) and
// AppleFileQueueProvider (dataloom-runtime) into one convenience wiring
// helper, mirroring how dataloom-android aggregates its four Android
// provider modules.
//
// Scope of this slice:
// - AppleConnectivityProvider: a single bounded synchronous query of the
//   current NWPathMonitor network path, translated into a ConnectivitySnapshot.
// - AppleSchedulerProvider: BGTaskScheduler-backed schedule()/cancel().
// - AppleDataLoomProviders / appleDataLoomProviders() / installAppleProviders():
//   real, public wiring code bundling the four core iOS providers, matching
//   dataloom-android's AndroidDataLoomProviders in shape and philosophy. See
//   docs/apple/dataloom-ios.md.
//
// Deliberately NOT in scope (future #101 slices):
// - iOS lifecycle integration (no LifecycleProvider contract exists in this
//   codebase at all yet)
// - secure/Keychain-backed platform integration (DataLoom never generates,
//   stores, resolves, or rotates key material -- see KeyReference's own
//   KDoc; this is intentionally the host application's job)
// - actual device/simulator/CI runtime proof for any iOS slice
//
// Rules:
// - May depend on dataloom-api (and, transitively, dataloom-model and
//   dataloom-provider-api), dataloom-runtime (for AppleFileQueueProvider and
//   DataLoomBuilder), and dataloom-storage-sqldelight (for
//   SqlDelightStorageProvider / createIosSqlDelightStorageDatabase). Both of
//   those modules use the same io.dataloom.kotlin.multiplatform-library
//   convention plugin as this module, which gates their iOS targets behind
//   the identical isAppleHost || dataloom.appleKlibCrossCompile condition
//   this module's own inclusion in settings.gradle.kts uses -- no gating
//   mismatch.
// - Must not depend on dataloom-core implementation internals or
//   dataloom-testing.
// - Must not leak NWPathMonitor, nw_path_t, dispatch queue,
//   BGTaskScheduler, BGTaskRequest, or any other Apple platform type through
//   its public API.
// - AppleDataLoomProviders and its factory/installer live in src/iosMain
//   (not src/commonMain): they reference AppleFileQueueProvider and
//   createIosSqlDelightStorageDatabase, both iOS-only declarations in their
//   respective modules' own iosMain source sets, which are not part of
//   those modules' common API surface.
//
// Targets: iosArm64, iosSimulatorArm64, iosX64 -- matching dataloom-apple's
// target declaration style. This module is included in the Gradle build only
// on macOS hosts, or when klib cross-compilation is explicitly requested via
// -Pdataloom.appleKlibCrossCompile=true (see settings.gradle.kts), the same
// convention dataloom-apple already uses.
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

@OptIn(ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()

    // Declare explicit Apple targets, matching dataloom-apple's target set.
    iosArm64()          // physical iPhone / iPad devices
    iosSimulatorArm64() // Apple-silicon iOS simulator
    iosX64()            // Intel iOS simulator (Rosetta / legacy runner)

    sourceSets {
        commonMain {
            dependencies {
                // DataLoom public API contracts (ConnectivityProvider,
                // ConnectivitySnapshot, canonical error types).
                api(project(":dataloom-api"))
                // DataLoomBuilder facade and the production
                // AppleFileQueueProvider QueueProvider implementation.
                api(project(":dataloom-runtime"))
                // SqlDelightStorageProvider and the iOS SQLDelight database
                // factory (createIosSqlDelightStorageDatabase).
                api(project(":dataloom-storage-sqldelight"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
