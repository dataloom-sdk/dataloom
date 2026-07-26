/**
 * Convention plugin for DataLoom Android library modules.
 *
 * Applying this plugin configures:
 * - Android library plugin (com.android.library) with compileSdk, minSdk,
 *   testInstrumentationRunner, and consumerProguardFiles defaults.
 * - Kotlin Android plugin (org.jetbrains.kotlin.android) for Kotlin compilation.
 * - Java and Kotlin compatibility set to 17.
 * - Unit-test resources included in local JVM unit tests.
 * - Release build type with minification disabled (library modules defer
 *   minification to the host application).
 *
 * Each module that applies this plugin must additionally set:
 * - `android { namespace = "..." }` — unique package namespace for the module.
 *
 * Module-specific dependencies and additional plugin configuration are added in
 * each module's own build file.
 *
 * Applying both plugins together in a single convention plugin ensures that both
 * com.android.library and org.jetbrains.kotlin.android are resolved within the
 * same build-logic classloader, avoiding NoClassDefFoundError for AGP internal
 * types (such as com.android.build.gradle.BaseExtension) that arise from Gradle's
 * plugin classloader isolation in composite builds.
 *
 * Plugin ID: io.dataloom.android.library
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

plugins.withId("com.android.library") {
    extensions.configure<com.android.build.api.dsl.LibraryExtension> {
        compileSdk = 35

        defaultConfig {
            minSdk = 21
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            consumerProguardFiles("consumer-rules.pro")
        }

        buildTypes {
            release {
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
}

// Configure Kotlin compilation for JVM 17 using the KGP 2.x API.
// This supersedes the legacy android { kotlinOptions { jvmTarget } } approach.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
