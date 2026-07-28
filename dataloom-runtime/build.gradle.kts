// DataLoom Runtime module.
//
// This module will house the future synchronization runtime, workflow
// orchestration, and engine coordination.
//
// Rules:
// - May depend on dataloom-api and dataloom-core.
// - Must not depend on dataloom-testing.
// - Must not expose internal implementation types publicly.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-model"))
                api(project(":dataloom-provider-api"))
                api(project(":dataloom-api"))
                implementation(project(":dataloom-core"))
            }
        }
    }
}
