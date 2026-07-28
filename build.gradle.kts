// The shared KMP plugin is resolved for production multiplatform modules.
// Android and KSP plugins are resolved only by the Android modules that apply
// them, avoiding their full dependency graph in non-Android validation.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}
