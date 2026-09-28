// DataLoom enterprise governance module (#99 / DL-045).
//
// Slice 1: closed RBAC model + deterministic evaluator (D7), tenant
// isolation guard (D8), and a tamper-evident, hash-chained audit log (D9).
// Slice 2: HMAC-SHA256 signed policy packs (D10) and the opt-in
// DataLoomBuilder.governanceConfiguration wiring. Decisions are recorded in
// docs/adr/ADR-0005-enterprise-governance-foundation.md and
// docs/adr/ADR-0010-governance-signed-policy-packs-and-runtime-wiring.md.
// Durable audit persistence, configuration locks, residency, and
// fleet/support diagnostics are later slices.
//
// Rules:
// - Depends only on dataloom-api (policy foundation vocabulary) and its
//   transitive dataloom-model (identifiers, clocks, digest/HMAC primitives),
//   plus kotlinx.coroutines.core for the audit log's writer mutex.
// - Must remain platform-independent: no expect/actual in production code.
//   The HMAC calculator is injected; tests bind the real JVM and Apple
//   implementations from dataloom-model.
// - Only dataloom-runtime may depend on it (api, for DataLoom.governance,
//   ADR-0010); it must not depend on dataloom-core, dataloom-runtime, or
//   dataloom-testing.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    explicitApi()

    sourceSets {
        commonMain {
            dependencies {
                api(project(":dataloom-api"))
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
