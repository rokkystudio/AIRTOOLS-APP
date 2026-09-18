plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "fuck.system.hashcat"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        // Only arm64-v8a is built and shipped by the packaging pipeline.
        // Declaring ABIs that have no libhashcat_exec.so would produce an APK
        // that installs but crashes on launch for those devices.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("android/src/main/AndroidManifest.xml")
            assets.srcDir("build/generated/hashcatRuntime/assets")
            jniLibs.srcDir("build/generated/hashcatRuntime/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libhashcat_exec.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

