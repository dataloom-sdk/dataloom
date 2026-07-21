/**
 * Convention plugin for DataLoom Kotlin Multiplatform library modules.
 *
 * Applying this plugin configures:
 * - Kotlin Multiplatform with an initial JVM target.
 * - Java toolchain 17 for consistent host-side compilation.
 * - JVM bytecode target 17.
 * - Common and JVM source sets.
 * - `kotlin-test` in `commonTest` for cross-platform test utilities.
 * - JUnit Platform for JVM test execution.
 * - Reproducible archive output (no embedded timestamps, ordered entries).
 *
 * Plugin ID: io.dataloom.kotlin.multiplatform-library
 */
plugins {
    kotlin("multiplatform")
}

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
