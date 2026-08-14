// DataLoom plugin SPI module.
//
// This module contains the stable plugin manifest, permission, lifecycle,
// compatibility, and bounded-execution *contracts* required by #93 (DL-039)
// to freeze the V1 published artifact graph. It intentionally contains no
// plugin loading, registration, enforcement, isolation, or certification
// behavior — that engine is #98 (DL-044 plugin platform)'s job, built on
// top of these contracts. See docs/api/plugin-api.md for the exact scope
// boundary between this module and #98.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-model"))
            }
        }
    }
}
