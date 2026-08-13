plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.autobots"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.autobots.camera"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("appVersionName") as String? ?: "0.0.0"

        // Only arm64 matters: the QNN libraries in jniLibs/ are arm64-only, and every
        // target device for this app is arm64. Keeps the APK from carrying dead ABIs.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared" }
        }
    }

    externalNativeBuild {
        // Tiny JNI shim over the QNN TFLite delegate — QAIRT ships a C API and no Java
        // binding, so there is no way to reach TfLiteQnnDelegateCreate() from Kotlin
        // without it. See src/main/cpp/qnn_delegate_jni.cpp.
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    buildTypes {
        // Phase 0 instrumentation. On by default in debug, off in release.
        // Override either way with -PcamPerf=true|false
        val camPerfOverride = (findProperty("camPerf") as String?)?.toBoolean()
        debug {
            buildConfigField("boolean", "CAM_PERF", (camPerfOverride ?: true).toString())
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "CAM_PERF", (camPerfOverride ?: false).toString())
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // LiteRT memory-maps the model straight out of the APK, which only works if the
        // file is stored uncompressed. Without this the interpreter fails to load at
        // runtime with no useful message. Same reason for the QNN context binary.
        noCompress += listOf("tflite", "bin", "dlc")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // The QNN HTP backend loads its DSP-side skel by *file path* through
            // ADSP_LIBRARY_PATH, so the .so files have to exist on disk rather than stay
            // compressed inside the APK. Costs install size; without it the NPU backend
            // cannot start at all.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.camerax.video)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.pose.detection)
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.gpu.api)
    implementation(libs.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Ktor Server for Remote Live View / Control
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
