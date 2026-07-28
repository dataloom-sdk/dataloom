// DataLoom foundational model module.
//
// This is the bottom of the production dependency graph. It contains only
// platform-neutral value types, identifiers, canonical errors, metadata, and
// time contracts. It must not depend on another DataLoom module.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    explicitApi()
}
