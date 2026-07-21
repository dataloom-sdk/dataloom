// DataLoom Core module.
//
// This module provides internal, platform-independent foundations shared
// by runtime components.
//
// Rules:
// - May depend on dataloom-api.
// - Must not depend on dataloom-runtime or dataloom-testing.
// - Internal implementation details must not be exposed as public API.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":dataloom-api"))
            }
        }
    }
}
