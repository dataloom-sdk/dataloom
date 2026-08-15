// DataLoom configuration module.
//
// Published as `dataloom-config` per ADR-0002: typed configuration,
// versioned immutable snapshots, deterministic precedence/rollback history,
// and integrity checksums. Moved out of dataloom-api (#236/#93) into its
// own artifact/module — it had zero callers outside its own package and
// depends only on dataloom-model, so it was never actually coupled to
// dataloom-api; the split now matches ADR-0002's intended graph, where
// dataloom-config is a peer of dataloom-api, not a sub-dependency of it.
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
