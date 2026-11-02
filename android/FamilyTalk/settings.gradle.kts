// Set FAMILYTALK_CORE_ONLY=1 to build and unit-test the pure-JVM :core module on a
// machine without Google's Maven repository / the Android SDK. :app is then skipped.
val coreOnly = System.getenv("FAMILYTALK_CORE_ONLY") != null

pluginManagement {
    repositories {
        if (System.getenv("FAMILYTALK_CORE_ONLY") == null) google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("FAMILYTALK_CORE_ONLY") == null) google()
        mavenCentral()
        if (System.getenv("FAMILYTALK_CORE_ONLY") == null) {
            // Linphone SDK (SIP engine, GPLv3) is only published on Belledonne's own repository.
            maven {
                url = uri("https://download.linphone.org/maven_repository")
                content { includeGroup("org.linphone") }
            }
        }
    }
}
rootProject.name = "FamilyTalk"
include(":core")
if (!coreOnly) {
    include(":app")
}
