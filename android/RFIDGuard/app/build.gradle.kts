import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val appVersionName = "1.0.0"
val appVersionCode = (System.getenv("RFIDGUARD_VERSION_CODE") ?: "1").toInt()

// Release signing: provide a keystore through environment variables (CI) or a
// keystore.properties file next to this build file. Falls back to the debug key so
// a release build is always installable.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
val releaseStoreFile = signingValue("RFIDGUARD_KEYSTORE", "storeFile")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.mypro.rfidguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mypro.rfidguard"
        // Android 11+: needed for hardware-backed keys that accept biometrics or the screen lock per use.
        minSdk = 30
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("RFIDGUARD_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("RFIDGUARD_KEY_ALIAS", "keyAlias") ?: "rfidguard"
                keyPassword = signingValue("RFIDGUARD_KEY_PASSWORD", "keyPassword") ?: signingValue("RFIDGUARD_KEYSTORE_PASSWORD", "storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
