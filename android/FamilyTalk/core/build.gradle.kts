plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // BouncyCastle's lightweight API (not the JCA provider) gives identical Ed25519 /
    // X25519 / ChaCha20-Poly1305 / HKDF on every Android version and on the JVM.
    api(libs.bouncycastle)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.paho.mqtt)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test> {
    useJUnit()
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
