import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val packagedNotices by tasks.registering(Sync::class) {
    from(rootProject.files("LICENSE", "THIRD_PARTY_NOTICES.md"))
    into(layout.buildDirectory.dir("generated/rivet-notices"))
}

android {
    namespace = "com.kaiser.rivet"
    compileSdk = 35

    // Use a narrow generated asset directory; using the project root here
    // overlaps Gradle lint outputs and breaks task dependency validation.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/rivet-notices"))

    defaultConfig {
        applicationId = "com.kaiser.rivet"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.8.0"
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

tasks.named("preBuild").configure {
    dependsOn(packagedNotices)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":terminal-view"))
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
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
