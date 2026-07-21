// Build logic for DataLoom convention plugins.
//
// The `kotlin-dsl` plugin enables writing Gradle convention plugins in Kotlin DSL.
// The Kotlin Gradle Plugin dependency is added so that convention plugins can
// apply `kotlin("multiplatform")` without importing additional plugins.
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
