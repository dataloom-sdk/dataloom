// Build-logic-android provides the io.dataloom.android.library convention
// plugin for all DataLoom Android library modules.
//
// This included build is only activated when DATALOOM_ANDROID_BUILD=true,
// keeping dl.google.com and the Android Gradle Plugin entirely out of
// KMP-only (PR validation, JVM, Apple) builds.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
}

dependencies {
    // Kotlin Gradle plugin — required so convention plugins can reference
    // KGP APIs (KotlinJvmCompile, JvmTarget) at build-logic-android compile time.
    implementation(libs.kotlin.gradlePlugin)
    // Android Gradle Plugin — required to compile against LibraryExtension and
    // to keep both com.android.library and org.jetbrains.kotlin.android in the
    // same build-logic-android classloader, resolving the Gradle 9 plugin
    // classloader isolation that caused NoClassDefFoundError for BaseExtension.
    implementation(libs.android.gradlePlugin)
}
