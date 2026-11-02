// Root build file. Plugins are declared per module so the JVM-only :core module
// can be built without resolving the Android Gradle Plugin.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
