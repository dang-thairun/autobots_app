plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Upload backend defaults from the repo-root `.env`.
 *
 * Android has no runtime notion of a dotenv, so the values are read here and baked into
 * BuildConfig. Missing file or missing key both yield an empty string, which the app treats
 * as "ask the operator" rather than as an error — see UploadSettings.seedFromDefaults.
 *
 * **These end up readable inside the APK.** Anyone with the file can extract UPLOAD_TOKEN.
 * That is acceptable for a build you hand to your own field devices and not acceptable for
 * anything published; leave the token blank in that case and provision by QR instead.
 */
fun dotenv(): Map<String, String> {
    val file = rootProject.file(".env")
    if (!file.exists()) return emptyMap()
    return file.readLines()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            line.substringBefore('=').trim() to
                line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
        }
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

        val env = dotenv()
        listOf(
            "UPLOAD_GRAPHQL_URL",
            "UPLOAD_COMPLETE_URL",
            "UPLOAD_PLATFORM",
            "UPLOAD_EVENT_ID",
            "UPLOAD_TOKEN",
        ).forEach { key ->
            val value = env[key].orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")
            buildConfigField("String", key, "\"$value\"")
        }

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

// Room schema JSON is committed to the repo: every migration written after this point is
// diffed against it. Without the export Room only warns, and the warning is easy to miss.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.tflite)
    implementation(libs.tflite.gpu)
    implementation(libs.tflite.gpu.api)
    implementation(libs.coroutines.android)

    // Upload queue (B3a) — see docs/PHASES.md. Room stays in :androidApp; :shared is a KMP
    // module and KSP there is a different, harder setup that this queue has no need for.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Ktor Server for Remote Live View / Control
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
}
