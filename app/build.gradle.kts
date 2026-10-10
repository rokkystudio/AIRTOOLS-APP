plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "fuck.system.airtools"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "fuck.system.airtools"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "1.11"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation(project(":neoui"))
    implementation(libs.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
}
