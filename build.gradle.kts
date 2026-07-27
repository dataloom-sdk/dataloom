// Root plugin declarations keep the Kotlin, Android, and KSP plugin artifacts
// on one build classpath. This is required by AGP 9 built-in Kotlin when the
// repository also uses Kotlin Multiplatform convention plugins.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
}
