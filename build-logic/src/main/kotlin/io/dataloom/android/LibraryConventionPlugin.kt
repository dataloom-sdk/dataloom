package io.dataloom.android

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import com.android.build.api.dsl.LibraryExtension

/**
 * Binary convention plugin for DataLoom Android library modules.
 *
 * Applying this plugin configures:
 * - Android library plugin (com.android.library) with compileSdk, minSdk,
 *   testInstrumentationRunner, and consumerProguardFiles defaults.
 * - Java and Kotlin compatibility set to 17 via AGP 9+ built-in Kotlin
 *   (org.jetbrains.kotlin.android is not applied separately; AGP 9 supplies
 *   Kotlin compilation natively and propagates compileOptions.targetCompatibility
 *   to the Kotlin compiler).
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
 * Plugin ID: io.dataloom.android.library
 */
class LibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // Apply the Android library plugin.
            // AGP 9+ supplies built-in Kotlin support; the separate
            // org.jetbrains.kotlin.android plugin must not be applied.
            pluginManager.apply("com.android.library")

            // Configure Android extension using the stable public DSL API.
            extensions.configure(LibraryExtension::class.java) {
                compileSdk = 35

                defaultConfig {
                    minSdk = 21
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    consumerProguardFiles("consumer-rules.pro")
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

                testOptions {
                    unitTests {
                        isIncludeAndroidResources = true
                    }
                }
            }
        }
    }
}
