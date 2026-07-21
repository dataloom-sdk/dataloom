pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "dataloom"

// Build-logic contains reusable convention plugins for all DataLoom modules.
includeBuild("build-logic")

include(
    ":dataloom-api",
    ":dataloom-core",
    ":dataloom-runtime",
    ":dataloom-testing",
)
