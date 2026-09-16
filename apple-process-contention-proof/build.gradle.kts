// Apple cross-process probe-contention CI proof harness (#94/#95).
//
// This module exists solely so two independently-launched, independently
// bundle-identified iOS Simulator apps can link against a small,
// deliberately narrow Objective-C/Swift-visible surface and race for real
// against the production CircuitBreakerCoordinator/CircuitBreakerExecutionGate
// pair. It is not a production distribution module: it is never included in
// dataloom-apple's "DataLoom" XCFramework, and no host application is
// expected to depend on it directly. See
// docs/apple/cross-process-contention-investigation.md for the full
// investigation this module acts on (why App Groups and app-extension
// alternatives dead-end on a paid-Apple-Developer-account wall, and why the
// iOS Simulator's own lack of app-container sandboxing is the mechanism this
// module actually relies on instead) and
// docs/apple/process-contention-proof.md for the CI shape.
//
// Why a separate module rather than extending apple-process-termination-proof:
// - That module's own XCFramework ("DataLoomProcessTerminationProof") and app
//   target are already wired into a proven, green CI job (#94's single-process
//   kill/relaunch proof). This module is new, unproven infrastructure with a
//   materially different shape (two app targets racing via a shared
//   host-filesystem directory, not one app kill/relaunch cycle) and must not
//   risk that already-proven job.
// - Keeping this module's own public API to two methods
//   (AppleCircuitBreakerProbeContentionProof.openCircuitAndSignalReady/
//   waitForGoSignalThenAttemptProbe) taking/returning only String,
//   Int, Long, and the plain ProcessContentionProofResult data holder keeps
//   the generated Objective-C/Swift surface small and predictable, matching
//   apple-process-termination-proof's own precedent.
//
// Real production code exercised: the full CircuitBreakerCoordinator/
// CircuitBreakerExecutionGate pair (dataloom-runtime, commonMain) against
// AppleFileCircuitBreakerStateStore (dataloom-runtime, src/iosMain) -- the
// exact pair CircuitBreakerProbeContentionContentProviderBase drives for the
// Android precedent this module mirrors, unlike
// AppleCircuitBreakerProcessTerminationProof (#94's single-process proof),
// which deliberately drives the lower-level store directly.
//
// Targets: iosArm64, iosSimulatorArm64, iosX64 -- matching
// apple-process-termination-proof's target declaration style, and gated
// identically in settings.gradle.kts (macOS host, or
// -Pdataloom.appleKlibCrossCompile=true for Windows/Linux klib
// cross-compilation verification only -- actual framework *linking* still
// requires a macOS host/toolchain).
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

@OptIn(ExperimentalAbiValidation::class)
kotlin {
    explicitApi()
    abiValidation()

    val proofXCFramework = XCFramework("DataLoomProcessContentionProof")

    listOf(
        iosArm64(),          // physical iPhone / iPad devices (unused by the simctl proof, kept for parity)
        iosSimulatorArm64(), // Apple-silicon iOS simulator -- what macos-15 CI runners actually execute
        iosX64(),            // Intel iOS simulator (Rosetta / legacy runner)
    ).forEach { target ->
        target.binaries.framework {
            baseName = "DataLoomProcessContentionProof"
            binaryOption("bundleId", "io.dataloom.processcontentionproof.kmp")
            isStatic = true
            proofXCFramework.add(this)
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                // Not exported -- see the file header. Used only to build
                // the internal circuit-breaker contention path.
                implementation(project(":dataloom-api"))
                implementation(project(":dataloom-runtime"))
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        matching { it.name == "iosMain" }.configureEach {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
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
