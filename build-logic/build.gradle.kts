plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
}

// Mirror the root project's conditional inclusion of Android modules.
// When DATALOOM_ANDROID_BUILD=true, the Android Gradle Plugin must be in the
// build-logic classpath so that KGP's AgpWithBuiltInKotlinAppliedCheck can
// resolve com.android.build.gradle.BaseExtension from the shared build-logic
// classloader.  Without this, Gradle's composite-build classloader isolation
// causes a NoClassDefFoundError when kotlin.android and com.android.library
// are both applied to an Android module.
val isAndroidBuildEnabled: Boolean = System.getenv("DATALOOM_ANDROID_BUILD") == "true"

dependencies {
    // Provides the Kotlin Multiplatform Gradle plugin to convention plugin scripts.
    implementation(libs.kotlin.gradlePlugin)
    // Provides the Android Gradle Plugin to the build-logic classloader when
    // building Android modules, enabling KGP/AGP classloader compatibility.
    if (isAndroidBuildEnabled) {
        implementation(libs.android.gradlePlugin)
    }
}
