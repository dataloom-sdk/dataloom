// Build logic for DataLoom convention plugins.
//
// The `kotlin-dsl` plugin enables writing Gradle convention plugins in Kotlin DSL.
// The Kotlin Gradle Plugin dependency is added so that convention plugins can
// apply `kotlin("multiplatform")` without importing additional plugins.
//
// Android-specific convention plugins (io.dataloom.android.library) are
// conditionally included and compiled only when DATALOOM_ANDROID_BUILD=true,
// keeping the Android Gradle Plugin and dl.google.com entirely out of
// KMP-only (PR validation, JVM, Apple) builds.
val isAndroidBuildEnabled = System.getenv("DATALOOM_ANDROID_BUILD") == "true"

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    if (isAndroidBuildEnabled) {
        google()
    }
}

dependencies {
    // Provides the Kotlin Multiplatform Gradle plugin to convention plugin scripts.
    implementation(libs.kotlin.gradlePlugin)
    if (isAndroidBuildEnabled) {
        // Provides the Android Gradle Plugin to convention plugin scripts,
        // and ensures both KGP and AGP are on the same classloader.
        implementation(libs.android.gradlePlugin)
    }
}

sourceSets {
    main {
        kotlin {
            if (!isAndroidBuildEnabled) {
                exclude("**/io/dataloom/android/**")
            }
        }
    }
}

gradlePlugin {
    plugins {
        if (isAndroidBuildEnabled) {
            register("androidLibrary") {
                id = "io.dataloom.android.library"
                implementationClass = "io.dataloom.android.LibraryConventionPlugin"
            }
        }
    }
}
