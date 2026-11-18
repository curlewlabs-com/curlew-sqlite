// The standalone build of the CurlewSQLite Android library, for its own tests and CI. A
// consumer includes the curlewsqlite module in its own build instead, and this file is
// not read there.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "curlew-sqlite"
include(":curlewsqlite")
