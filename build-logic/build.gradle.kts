// Build logic for DataLoom convention plugins.
//
// The `kotlin-dsl` plugin enables writing Gradle convention plugins in Kotlin DSL.
// The Kotlin Gradle Plugin dependency is added so that convention plugins can
// apply `kotlin("multiplatform")` without importing additional plugins.
//
// Android-specific convention plugins (io.dataloom.android.library) live in the
// build-logic-android included build, which is activated only when
// DATALOOM_ANDROID_BUILD=true.  Keeping them separate ensures that the Android
// Gradle Plugin and dl.google.com are never required for KMP-only builds.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Provides the Kotlin Multiplatform Gradle plugin to convention plugin scripts.
    implementation(libs.kotlin.gradlePlugin)
}
