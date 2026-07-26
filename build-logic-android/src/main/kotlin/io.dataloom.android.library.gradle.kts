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
 * same build-logic-android classloader, avoiding NoClassDefFoundError for AGP
 * internal types (such as com.android.build.gradle.BaseExtension) that arise from
 * Gradle 9's plugin classloader isolation in composite builds.
 *
 * Why apply<KotlinAndroidPluginWrapper>() instead of id("org.jetbrains.kotlin.android")
 * in the plugins {} block?
 *
 * Precompiled script plugins resolve plugins declared in the plugins {} block using the
 * root build's pluginManagement (not the included build's classloader). When
 * build-logic is also included in pluginManagement, Gradle finds org.jetbrains.kotlin.android
 * in build-logic's plugin registry (because kotlin-gradle-plugin is on build-logic's
 * implementation classpath). build-logic's classloader has KGP but NOT AGP, so when
 * KotlinAndroidPlugin.apply() tries to cast to BaseExtension (an AGP type), it fails
 * with NoClassDefFoundError.
 *
 * Using apply<KotlinAndroidPluginWrapper>() in the script body (not the plugins {} block)
 * loads the class via the convention plugin's own classloader (build-logic-android's),
 * which contains both KGP and AGP. BaseExtension is then accessible. ✓
 *
 * Plugin ID: io.dataloom.android.library
 */
plugins {
    id("com.android.library")
}

// Apply the Kotlin Android plugin using a direct class reference from the
// build-logic-android classloader (which contains both KGP and AGP).
// See the KDoc above for why this is not done via id("org.jetbrains.kotlin.android")
// in the plugins {} block.
apply<org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper>()

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
