// Set MINUTES_CORE_ONLY=1 to build and unit-test the pure-JVM :core module on a
// machine that cannot reach Google's Maven repository. The Android :app module is
// then left out of the build.
val coreOnly = System.getenv("MINUTES_CORE_ONLY") != null

pluginManagement {
    repositories {
        if (System.getenv("MINUTES_CORE_ONLY") == null) google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("MINUTES_CORE_ONLY") == null) google()
        mavenCentral()
    }
}
rootProject.name = "MeetingMinutes"
include(":core")
if (!coreOnly) {
    include(":app")
}
