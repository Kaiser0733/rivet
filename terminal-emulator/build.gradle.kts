plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            ndkBuild {
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
}
