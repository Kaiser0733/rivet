plugins {
    id("com.android.library")
}

android {
    namespace = "com.kaiser.rivet.toolchain"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // The toolchain is arm64 only (plan §4.5). The app module keeps its universal
        // four-ABI payload and the release stays a single APK; this filter only trims
        // this module's native payload, which is where libbusybox.so lands.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
