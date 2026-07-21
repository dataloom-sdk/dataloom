// DataLoom Testing module.
//
// This module will provide testing utilities including fake providers,
// controlled clocks, controlled schedulers, test fixtures, and
// failure-injection utilities.
//
// Rules:
// - May depend on dataloom-api and dataloom-core.
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
            }
        }
    }
}
