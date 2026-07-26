/**
 * Convention plugin for DataLoom Kotlin Multiplatform library modules.
 *
 * Applying this plugin configures:
 * - Kotlin Multiplatform with a JVM target.
 * - Apple targets (iosArm64, iosSimulatorArm64, iosX64) on macOS hosts.
 * - Java toolchain 17 for consistent host-side compilation.
 * - JVM bytecode target 17.
 * - Common and JVM source sets.
 * - Shared iosMain / iosTest source sets (macOS hosts only).
 * - `kotlin-test` in `commonTest` for cross-platform test utilities.
 * - JUnit Platform for JVM test execution.
 * - Reproducible archive output (no embedded timestamps, ordered entries).
 *
 * ## Apple target availability
 *
 * iosArm64, iosSimulatorArm64, and iosX64 targets are declared only when the
 * build runs on a macOS host.  Apple SDK tools (Xcode, ld) required for
 * Kotlin/Native linking and XCFramework assembly are unavailable on Linux or
 * Windows.  The macOS CI job validates all Apple-target compilation, simulator
 * tests, and XCFramework assembly.  The Linux CI job validates JVM targets
 * only and remains unchanged.
 *
 * iosX64 (Intel iOS simulator) is included because Kotlin 2.4.10 supports it
 * and the host macOS toolchain provided by the CI runner covers it.
 *
 * Plugin ID: io.dataloom.kotlin.multiplatform-library
 */
plugins {
    kotlin("multiplatform")
}

// Detect the host operating system to guard Apple-platform target declarations.
// System.getProperty("os.name") is used here (consistent with settings.gradle.kts)
// because it is available in both settings-phase and build-phase scripts and
// does not require importing Gradle internal APIs.
val isAppleHost: Boolean = (System.getProperty("os.name") ?: "")
    .lowercase()
    .contains("mac")

kotlin {
    // Use Java toolchain 17 for compilation and tool-chain consistency.
    jvmToolchain(17)

    jvm {
        // Emit JVM 17 bytecode.
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        // Run JVM tests with the JUnit Platform.
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // Apple targets are declared only on macOS hosts where the Apple SDK and
    // Xcode toolchain are available.  On Linux CI, these targets are absent so
    // the JVM build and tests remain green.
    if (isAppleHost) {
        iosArm64()          // physical iPhone / iPad devices
        iosSimulatorArm64() // Apple-silicon iOS simulator
        iosX64()            // Intel iOS simulator (Rosetta compatibility)
        // The Kotlin 2.x default hierarchy template automatically creates the
        // shared iosMain and iosTest source sets for the three iOS targets above.
    }

    sourceSets {
        commonMain {
            dependencies {
                // Production common dependencies are added in individual module build files.
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        jvmMain {
            dependencies {
                // JVM-specific production dependencies are added in individual module build files.
            }
        }
        jvmTest {
            dependencies {
                // JVM-specific test dependencies are added in individual module build files.
            }
        }
    }
}

// Reproducible archive output: remove embedded timestamps and enforce ordering.
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
