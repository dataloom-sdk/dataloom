// DataLoom Testing module.
//
// This module provides testing utilities including fake providers,
// controlled clocks, controlled schedulers, and failure-injection helpers.
//
// Rules:
// - May depend on dataloom-api, dataloom-core, and dataloom-runtime.
// - Must not be a dependency of production modules.
// - Must not be included in runtime implementation dependencies.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":dataloom-api"))
                implementation(project(":dataloom-core"))
                implementation(project(":dataloom-runtime"))
            }
        }
    }
}
