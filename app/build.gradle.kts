plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.forestnav.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.forestnav.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // TODO(nav): create forest-nav-release.keystore once the first build is
        // good enough to keep upgrading. Kept unsigned-for-now so the project
        // builds on any machine; swap in a signing config before distributing.
    }

    // one source tree, two installers: Android 9 (API 28) and Android 12 (API 31)
    flavorDimensions += "sdk"
    productFlavors {
        create("android9") {
            dimension = "sdk"
            targetSdk = 28
        }
        create("android12") {
            dimension = "sdk"
            targetSdk = 31
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":gcap-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.documentfile)
    implementation(libs.material)
    implementation("org.json:json:20240303")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
