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
