import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Универсальная сборка: все ABI в одном APK.
// Python 3.11 — единственная версия Chaquopy, которая поддерживает
// 32-битные ABI (armeabi-v7a, x86), поэтому она используется для всех.
val pythonVersion = "3.11"
val pythonBinary = "/home/exestor/.local/share/uv/python/cpython-3.11.17-linux-x86_64-gnu/bin/python3.11"
val targetAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

// Ключи подписи лежат в keystore.properties (не в git).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists()

android {
    namespace = "com.tgwsproxy19"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tgwsproxy19"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"

        ndk {
            abiFilters += targetAbis
        }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
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
