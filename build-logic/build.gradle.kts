plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
}

// When DATALOOM_ANDROID_BUILD=true the io.dataloom.android.library convention
// plugin and its AGP dependency are included in build-logic.  Both plugins
// (com.android.library and org.jetbrains.kotlin.android) are applied together
// inside that convention plugin, ensuring they share the same build-logic
// classloader.  This avoids the NoClassDefFoundError for AGP internal types
// (such as com.android.build.gradle.BaseExtension) that occurs in Gradle 9.x
// when the two plugins are applied from isolated plugin classloaders.
//
// When DATALOOM_ANDROID_BUILD=false (KMP-only builds) the Android convention
// plugin source and its AGP dependency are excluded entirely, so the build
// does not contact dl.google.com for AGP resolution.
val isAndroidBuildEnabled: Boolean = System.getenv("DATALOOM_ANDROID_BUILD") == "true"

if (isAndroidBuildEnabled) {
    sourceSets {
        main {
            kotlin.srcDir("src/android/kotlin")
        }
    }
}

dependencies {
    // Provides the Kotlin Multiplatform Gradle plugin to convention plugin scripts.
    implementation(libs.kotlin.gradlePlugin)
    // Provides the Android Gradle Plugin to build-logic when building Android
    // modules.  Both AGP and KGP are declared here so that the
    // io.dataloom.android.library convention plugin can apply and configure both
    // from within the same build-logic classloader, preventing Gradle's plugin
    // classloader isolation from hiding AGP types from KGP's compatibility checks.
    if (isAndroidBuildEnabled) {
        implementation(libs.android.gradlePlugin)
    }
}
