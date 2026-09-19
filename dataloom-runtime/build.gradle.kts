// DataLoom Runtime module.
//
// This module will house the future synchronization runtime, workflow
// orchestration, and engine coordination.
//
// Rules:
// - May depend on dataloom-api, dataloom-core, and dataloom-plugin.
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
                // DataLoom.pluginEngine's public signatures use dataloom-plugin's
                // result/request types and dataloom-plugin-api's identifiers.
                api(project(":dataloom-plugin-api"))
                api(project(":dataloom-plugin"))
                implementation(project(":dataloom-core"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
