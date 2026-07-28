// Compile-only fixture proving that a consumer can use the public runtime
// without placing dataloom-core on its declared dependency graph.
plugins {
    id("io.dataloom.kotlin.multiplatform-library")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":dataloom-model"))
                implementation(project(":dataloom-provider-api"))
                implementation(project(":dataloom-api"))
                implementation(project(":dataloom-runtime"))
            }
        }
    }
}

tasks.register("checkRuntimeExternalConsumer") {
    group = "verification"
    description = "Compiles the external public-runtime consumer fixture."
    dependsOn(tasks.named("compileKotlinJvm"))
}

tasks.named("check") {
    dependsOn("checkRuntimeExternalConsumer")
}
