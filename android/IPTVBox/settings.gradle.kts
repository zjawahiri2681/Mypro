// Set IPTVBOX_CORE_ONLY=1 to work on (and unit-test) the pure-JVM :core module on a
// machine that cannot reach Google's Maven repository. The Android :app module is
// then left out of the build.
val coreOnly = System.getenv("IPTVBOX_CORE_ONLY") != null

pluginManagement {
    repositories {
        if (System.getenv("IPTVBOX_CORE_ONLY") == null) google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("IPTVBOX_CORE_ONLY") == null) google()
        mavenCentral()
    }
}
rootProject.name = "IPTVBox"
include(":core")
if (!coreOnly) {
    include(":app")
}
