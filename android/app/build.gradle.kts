plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "np.ipohelper.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "np.ipohelper.app"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    // The signing key is re-created on the build machine from the APP_SIGNING_SEED secret
    // (tools/make_keystore.py), so every build has the same key and installs as an update.
    // It is never stored in the repository.
    val keyFile = rootProject.file("keystore/ipo-helper.p12")
    val keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
    signingConfigs {
        if (keyFile.exists()) create("release") {
            storeFile = keyFile
            storeType = "PKCS12"
            storePassword = keyPassword
            keyAlias = "ipohelper"
            this.keyPassword = keyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("np.ipohelper:core:1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
