package io.dataloom.android

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Binary convention plugin for DataLoom Android library modules.
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
 * Plugin ID: io.dataloom.android.library
 */
class LibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // Apply required plugins
            pluginManager.apply("com.android.library")
            pluginManager.apply("org.jetbrains.kotlin.android")

            // Configure Android extension using public API DSL types
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

            // Configure Kotlin compilation for JVM 17 using the KGP 2.x API.
            tasks.withType(KotlinJvmCompile::class.java).configureEach {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }
}
