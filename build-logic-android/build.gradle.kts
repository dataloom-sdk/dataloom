// Build logic for DataLoom Android-specific convention plugins.
//
// By isolating Android-specific convention plugins in a separate included build
// (build-logic-android) that does NOT declare a dependency on the Kotlin Gradle
// Plugin (KGP), we prevent KGP from being loaded in the classpath of our Android-only
// modules. This avoids classloader mixing and prevents KGP's Multiplatform/Android
// integration from incorrectly executing on pure Android modules, resolving the
// "KotlinAndroidTarget" and "BaseVariant" compatibility issues with AGP 9.

plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
}

dependencies {
    // Provides the Android Gradle Plugin to convention plugin scripts.
    // Explicitly contains no reference to libs.kotlin.gradlePlugin.
    implementation(libs.android.gradlePlugin)
}
