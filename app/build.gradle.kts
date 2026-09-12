import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.kaiser.rivet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kaiser.rivet"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        // Public android/android debug credentials. Committing this key lets
        // consecutive CI debug APKs install over each other without forcing an
        // uninstall; the release certificate is a different key that never
        // enters the repository.
        create("pinnedDebug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("pinnedDebug")
        }
        release {
            isMinifyEnabled = false
            // CI supplies release signing through these env vars (decoded
            // from GitHub Secrets). With any of them unset the release
            // stays unsigned — never a silent fallback to the debug key.
            // See RELEASE_PROCESS.md.
            val ksFile = System.getenv("RIVET_KEYSTORE_FILE")
            val ksPassword = System.getenv("RIVET_KEYSTORE_PASSWORD")
            val ksAlias = System.getenv("RIVET_KEY_ALIAS")
            val ksKeyPassword = System.getenv("RIVET_KEY_PASSWORD")
            if (ksFile != null && ksPassword != null && ksAlias != null &&
                ksKeyPassword != null && rootProject.file(ksFile).exists()
            ) {
                signingConfig = signingConfigs.create("production") {
                    storeFile = rootProject.file(ksFile)
                    storePassword = ksPassword
                    keyAlias = ksAlias
                    keyPassword = ksKeyPassword
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.03.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
