// Root build file. Common plugin aliases are declared with `apply false` so
// each module can apply them via convention plugins without the root project
// pulling them into the classpath unnecessarily.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
}
