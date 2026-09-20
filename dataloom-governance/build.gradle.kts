// DataLoom enterprise governance module (#99 / DL-045).
//
// First slice: closed RBAC model + deterministic evaluator (D7), tenant
// isolation guard (D8), and a tamper-evident, hash-chained audit log (D9).
// Decisions are recorded in
// docs/adr/ADR-0005-enterprise-governance-foundation.md. Signed policy packs
// (D10), DataLoomBuilder wiring, durable audit persistence, configuration
// locks, residency, and fleet/support diagnostics are later slices.
//
// Rules:
// - Depends only on dataloom-api (policy foundation vocabulary) and its
//   transitive dataloom-model (identifiers, clocks, digest/HMAC primitives),
//   plus kotlinx.coroutines.core for the audit log's writer mutex.
// - Must remain platform-independent: no expect/actual in production code.
//   The HMAC calculator is injected; tests bind the real JVM and Apple
//   implementations from dataloom-model.
// - Must not become a mandatory dependency of any existing module, and must
//   not depend on dataloom-core, dataloom-runtime, or dataloom-testing.
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
