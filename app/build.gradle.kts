plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// armv7 (старые устройства) собирается отдельным флагом:
//   ./gradlew assembleDebug                 -> arm64-v8a + x86_64, Python 3.12
//   ./gradlew assembleDebug -PlegacyArm=true -> armeabi-v7a, Python 3.11
val legacyArm = (findProperty("legacyArm") as String?)?.toBoolean() ?: false
val pythonVersion = if (legacyArm) "3.11" else "3.12"
val pythonBinary = if (legacyArm) {
    "/home/exestor/.local/share/uv/python/cpython-3.11.17-linux-x86_64-gnu/bin/python3.11"
} else {
    "/home/exestor/.local/share/uv/python/cpython-3.12.15-linux-x86_64-gnu/bin/python3.12"
}
val targetAbis = if (legacyArm) listOf("armeabi-v7a") else listOf("arm64-v8a", "x86_64")

android {
    namespace = "com.tgwsproxy19"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tgwsproxy19"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = if (legacyArm) "1.1-legacy" else "1.1"

        ndk {
            abiFilters += targetAbis
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        version = pythonVersion
        buildPython(pythonBinary)
        pip {
            install("cryptography==42.0.8")
            install("httpx[http2]==0.28.1")
            install("certifi")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
