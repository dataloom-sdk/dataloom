// Root declarations keep Kotlin, AGP, and KSP on one build classpath.
// AGP 9 built-in Kotlin creates KotlinAndroidTarget during Android plugin
// application, and that KGP type references AGP's BaseVariant API. Resolving
// the plugins in separate project classloaders therefore fails at configuration.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
}
