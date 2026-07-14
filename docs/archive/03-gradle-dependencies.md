# 03 — Gradle Dependencies

## Version Catalog (`gradle/libs.versions.toml`)

```toml
[versions]
# Core
kotlin                  = "2.0.20"
agp                     = "8.5.2"
kmp                     = "2.0.20"

# AndroidX / Lifecycle
androidx-core           = "1.13.1"
androidx-lifecycle      = "2.8.4"

# CameraX
camerax                 = "1.4.0-rc01"   # Use stable when available; rc has latest ZSL fixes

# Coroutines
coroutines              = "1.9.0"

# TFLite (for JNI C++ side — AAR provides headers)
tflite                  = "2.16.1"

# libyuv (via prebuilt — see note below)
libyuv                  = "0.0.1790"     # use prebuilt AAR or build from source

# Serialization (for config/result passing across KMP boundary)
serialization           = "1.7.2"

[libraries]
# AndroidX
androidx-core           = { module = "androidx.core:core-ktx",                 version.ref = "androidx-core" }
androidx-lifecycle-vm   = { module = "androidx.lifecycle:lifecycle-viewmodel",  version.ref = "androidx-lifecycle" }
androidx-lifecycle-rt   = { module = "androidx.lifecycle:lifecycle-runtime-ktx",version.ref = "androidx-lifecycle" }

# CameraX — use-cases
camerax-core            = { module = "androidx.camera:camera-core",            version.ref = "camerax" }
camerax-camera2         = { module = "androidx.camera:camera-camera2",         version.ref = "camerax" }
camerax-lifecycle       = { module = "androidx.camera:camera-lifecycle",        version.ref = "camerax" }
camerax-video           = { module = "androidx.camera:camera-video",            version.ref = "camerax" }

# TFLite Android runtime (includes GPU / NNAPI delegates)
tflite-gpu              = { module = "org.tensorflow:tensorflow-lite-gpu-delegate-plugin", version.ref = "tflite" }
tflite-gpu-api          = { module = "org.tensorflow:tensorflow-lite-gpu",      version.ref = "tflite" }
tflite-task-vision      = { module = "org.tensorflow:tensorflow-lite-task-vision", version.ref = "tflite" }
tflite-support          = { module = "org.tensorflow:tensorflow-lite-support",  version.ref = "tflite" }
# NOTE: For pure C++ TFLite — the C API headers come from:
#   tensorflow-lite-task-vision AAR unpacked, or flatbuffers + bazel build
#   See doc/06-face-detection-ai.md for CMakeLists.txt details

# Coroutines
coroutines-core         = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core",    version.ref = "coroutines" }
coroutines-android      = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }

# Serialization
serialization-json      = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }

[plugins]
kotlin-multiplatform    = { id = "org.jetbrains.kotlin.multiplatform",  version.ref = "kmp" }
kotlin-android          = { id = "org.jetbrains.kotlin.android",        version.ref = "kotlin" }
kotlin-serialization    = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
android-application     = { id = "com.android.application",             version.ref = "agp" }
android-library         = { id = "com.android.library",                 version.ref = "agp" }
```

---

## `shared/build.gradle.kts` (KMP Module)

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions { jvmTarget = "17" }
        }
    }

    // Future iOS targets (uncomment when ready)
    // listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
    //     iosTarget.binaries.framework { baseName = "Shared" }
    // }

    sourceSets {
        // ── commonMain ─────────────────────────────────────────────────
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }

        // ── androidMain ────────────────────────────────────────────────
        androidMain.dependencies {
            // CameraX
            implementation(libs.camerax.core)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.video)

            // TFLite (for Kotlin-side bridge, C++ side via CMake)
            implementation(libs.tflite.gpu)
            implementation(libs.tflite.gpu.api)
            implementation(libs.tflite.task.vision)
            implementation(libs.tflite.support)

            // Coroutines Android dispatcher
            implementation(libs.coroutines.android)

            // AndroidX
            implementation(libs.androidx.core)
            implementation(libs.androidx.lifecycle.vm)
            implementation(libs.androidx.lifecycle.rt)
        }
    }
}

android {
    namespace  = "com.autobots.camera"
    compileSdk = 35
    minSdk     = 26          // API 26 = Android 8.0; Thermal API needs API 29+, guard it

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-O3")   // optimise C++ inference
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DTFLITE_ENABLE_GPU=ON"
                )
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a")   // modern phones only; add x86_64 for emulator
        }
    }

    externalNativeBuild {
        cmake {
            path   = file("src/androidMain/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

---

## `androidApp/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace  = "com.autobots.sportsCamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.autobots.sportscamera"
        minSdk        = 29     // Thermal API requires API 29
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0.0-mvp"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.vm)
    implementation(libs.coroutines.android)
}
```

---

## AndroidManifest Permissions Required

```xml
<!-- Camera hardware -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.autofocus" />

<!-- Storage -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

<!-- Thermal / Power (no runtime permission needed — system API) -->
<!-- Foreground Service — for long-running camera sessions -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
```

---

## `gradle.properties` Settings

```properties
# Enable KMP
kotlin.mpp.enableCInteropCommonization=true

# CameraX concurrent use-case support
android.enableJetifier=false

# NDK optimisation
android.ndkVersion=27.0.11902837

# JVM heap for Gradle (C++ build is heavy)
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m

# Parallel build
org.gradle.parallel=true
org.gradle.caching=true
```
