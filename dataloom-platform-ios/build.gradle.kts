// DataLoom platform-ios module (#101 / DL-039A).
//
// First bounded slice of the eventual dataloom-ios platform artifact: a real
// ConnectivityProvider implementation for iOS, mirroring how
// dataloom-connectivity-android implements the same
// io.dataloom.api.connectivity.ConnectivityProvider contract for Android.
//
// Scope of this slice:
// - AppleConnectivityProvider: a single bounded synchronous query of the
//   current NWPathMonitor network path, translated into a ConnectivitySnapshot.
//
// Deliberately NOT in scope (future #101 slices):
// - iOS lifecycle integration
// - background execution (BGTaskScheduler equivalent)
// - files
// - secure/Keychain-backed platform integration
// - adapter aggregation across those capabilities into one "dataloom-ios"
//   convenience artifact, the way dataloom-android aggregates its four
//   Android provider modules
//
// Rules:
// - May depend on dataloom-api (and, transitively, dataloom-model and
//   dataloom-provider-api).
// - Must not depend on dataloom-core implementation internals or
//   dataloom-testing.
// - Must not leak NWPathMonitor, nw_path_t, dispatch queue, or other
//   Network.framework / Apple platform types through its public API.
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
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
