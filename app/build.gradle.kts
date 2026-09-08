plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.fortress"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.fortress.console"
        // minSdk 26 (Android 8.0) — UsageStatsManager granular stats and
        // ClipboardManager.OnPrimaryClipChangedListener behave consistently from here.
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.3.0"

        // Pinned NDK — CI (and local builds) install exactly this version via
        // sdkmanager ("ndk;27.0.12077973") so AGP never guesses.
        ndkVersion = "27.0.12077973"

        // Ship only 32/64-bit ARM — Fortress targets physical phones/tablets used
        // as security lab devices; x86 emulator images are intentionally excluded
        // because root heuristics (kallsyms, magisk paths) are meaningless there.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -fno-exceptions"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Local security tooling is signed by the operator, not Play App Signing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // The console is a Jetpack Compose UI (rebuild for demo parity); the
    // layout code is still 100% Kotlin, just declarative instead of Views.
    buildFeatures {
        buildConfig = true
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Compose console (versions resolved by the BOM; compiler ships with Kotlin 2.0)
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Coroutines drive the scan pipeline (StateFlow progress) and root shell I/O.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // OkHttp for the optional VirusTotal lookup (HTTPS, multipart upload).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // EncryptedSharedPreferences keeps the VT API key out of plaintext prefs.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
