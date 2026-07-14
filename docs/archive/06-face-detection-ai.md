# 06 — Edge-AI Face Detection

## Why NOT ML Kit

| | ML Kit | TFLite C++ (chosen) |
|--|--------|---------------------|
| Latency | ~20–50ms | **8–15ms** (GPU delegate) |
| Google Play Services | Required | ❌ No dependency |
| Custom model | ❌ Only built-in | ✅ YOLOv8-Face / custom |
| Hardware delegates | Limited | GPU, NNAPI, Hexagon DSP |
| C++ API | No | ✅ Full C API |
| Offline / remote areas | Risky | ✅ Always works |

---

## Recommended Model: YOLOv8n-Face

```
YOLOv8n-Face (nano variant)
├── Input: 640×640 RGB
├── Output: [1, 25200, 16] — bbox + confidence + 5 landmarks
├── Size: ~6MB TFLite FP16
├── Speed: ~10ms on Snapdragon 8xx GPU
└── Source: https://github.com/derronqi/yolov8-face
           Convert: ultralytics export format=tflite half=True imgsz=640
```

Alternative: **MediaPipe Face Detector** (BlazeFace Short-Range TFLite)
- ขนาดเล็กกว่า (~1MB), เร็วกว่า (~3ms), แต่ accuracy ต่ำกว่าในระยะไกล

---

## Architecture: JNI Bridge

```
Kotlin (JVM)                    C++ (NDK)
──────────────────              ──────────────────────────────────
TFLiteFaceDetector.kt           face_detector_jni.cpp
       │                              │
       │  JNI call: detect()          │
       │─────────────────────────────▶│
       │  (ByteArray NV21, w, h)      │
       │                              ├── libyuv: NV21 → RGB
       │                              ├── TFLite C API: run inference
       │                              ├── Post-process: NMS, decode bbox
       │                              └── Return: float[] [n, 5] per face
       │◀─────────────────────────────│
       │  float[] results             │
       │                              │
  FaceDetectionResult[]          (stack-allocated, no GC pressure)
```

---

## CMakeLists.txt

```cmake
# shared/src/androidMain/jni/CMakeLists.txt
cmake_minimum_required(VERSION 3.22.1)
project(autobots_face_detector CXX)

set(CMAKE_CXX_STANDARD 17)

# ── TFLite C API ────────────────────────────────────────────────────────────
# Option A: Use prebuilt .aar unzipped (simplest for CI)
# Download: https://github.com/google/flatbuffers/releases
# Or use: https://www.tensorflow.org/lite/guide/build_android

set(TFLITE_LIB_DIR ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI})

add_library(tensorflowlite SHARED IMPORTED)
set_target_properties(tensorflowlite PROPERTIES
    IMPORTED_LOCATION ${TFLITE_LIB_DIR}/libtensorflowlite.so
)

# ── libyuv (prebuilt) ───────────────────────────────────────────────────────
add_library(yuv STATIC IMPORTED)
set_target_properties(yuv PROPERTIES
    IMPORTED_LOCATION ${TFLITE_LIB_DIR}/libyuv.a
)

# ── Main JNI library ────────────────────────────────────────────────────────
add_library(
    autobots_detector
    SHARED
    face_detector_jni.cpp
    face_postprocessor.cpp
)

target_include_directories(autobots_detector PRIVATE
    ${CMAKE_SOURCE_DIR}/include/tflite
    ${CMAKE_SOURCE_DIR}/include/libyuv
)

target_link_libraries(autobots_detector
    tensorflowlite
    yuv
    android
    log
    jnigraphics   # for Bitmap pixel access if needed
)

target_compile_options(autobots_detector PRIVATE
    -O3
    -ffast-math
    -DNDEBUG
)
```

---

## `face_detector_jni.cpp`

```cpp
// shared/src/androidMain/jni/face_detector_jni.cpp
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>

// TFLite C API
#include "tensorflow/lite/c/c_api.h"
#include "tensorflow/lite/delegates/gpu/delegate.h"
#include "tensorflow/lite/delegates/nnapi/nnapi_delegate.h"

// libyuv for colour conversion
#include "libyuv/convert.h"
#include "libyuv/convert_argb.h"

#define LOG_TAG "AutoBotsDetector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Global model state ───────────────────────────────────────────────────────
static TfLiteModel*         g_model       = nullptr;
static TfLiteInterpreter*   g_interpreter = nullptr;
static TfLiteDelegate*      g_delegate    = nullptr;

// ── NMS helper ───────────────────────────────────────────────────────────────
struct Detection {
    float x1, y1, x2, y2, score;
};

static float iou(const Detection& a, const Detection& b) {
    float ix1 = std::max(a.x1, b.x1);
    float iy1 = std::max(a.y1, b.y1);
    float ix2 = std::min(a.x2, b.x2);
    float iy2 = std::min(a.y2, b.y2);
    float inter = std::max(0.f, ix2 - ix1) * std::max(0.f, iy2 - iy1);
    float ua    = (a.x2-a.x1)*(a.y2-a.y1) + (b.x2-b.x1)*(b.y2-b.y1) - inter;
    return ua > 0 ? inter / ua : 0.f;
}

static std::vector<Detection> nms(std::vector<Detection> dets, float iou_thresh) {
    std::sort(dets.begin(), dets.end(), [](const Detection& a, const Detection& b) {
        return a.score > b.score;
    });
    std::vector<Detection> kept;
    std::vector<bool> suppressed(dets.size(), false);
    for (size_t i = 0; i < dets.size(); ++i) {
        if (suppressed[i]) continue;
        kept.push_back(dets[i]);
        for (size_t j = i + 1; j < dets.size(); ++j) {
            if (iou(dets[i], dets[j]) > iou_thresh) suppressed[j] = true;
        }
    }
    return kept;
}

// ── JNI Functions ────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_autobots_camera_detection_TFLiteFaceDetector_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray modelBytes,
        jboolean useGpu) {

    jsize   modelLen  = env->GetArrayLength(modelBytes);
    jbyte*  modelData = env->GetByteArrayElements(modelBytes, nullptr);

    g_model = TfLiteModelCreate(modelData, modelLen);
    env->ReleaseByteArrayElements(modelBytes, modelData, JNI_ABORT);

    if (!g_model) { LOGE("Failed to create TFLite model"); return JNI_FALSE; }

    TfLiteInterpreterOptions* options = TfLiteInterpreterOptionsCreate();
    TfLiteInterpreterOptionsSetNumThreads(options, 2);

    if (useGpu) {
        TfLiteGpuDelegateOptionsV2 gpu_opts = TfLiteGpuDelegateOptionsV2Default();
        gpu_opts.inference_preference = TFLITE_GPU_INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER;
        g_delegate = TfLiteGpuDelegateV2Create(&gpu_opts);
        TfLiteInterpreterOptionsAddDelegate(options, g_delegate);
        LOGI("GPU delegate enabled");
    }

    g_interpreter = TfLiteInterpreterCreate(g_model, options);
    TfLiteInterpreterOptionsDelete(options);

    if (!g_interpreter) { LOGE("Failed to create interpreter"); return JNI_FALSE; }
    if (TfLiteInterpreterAllocateTensors(g_interpreter) != kTfLiteOk) {
        LOGE("Failed to allocate tensors"); return JNI_FALSE;
    }

    LOGI("TFLite Face Detector initialised (GPU=%d)", useGpu);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_autobots_camera_detection_TFLiteFaceDetector_nativeDetect(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray nv21Bytes,
        jint srcWidth,
        jint srcHeight,
        jfloat confThresh) {

    if (!g_interpreter) return env->NewFloatArray(0);

    const int MODEL_INPUT = 640;

    // ── 1. NV21 → RGB (libyuv) ─────────────────────────────────────────────
    jbyte* nv21 = env->GetByteArrayElements(nv21Bytes, nullptr);

    std::vector<uint8_t> rgbBuf(MODEL_INPUT * MODEL_INPUT * 3);
    std::vector<uint8_t> scaledNv21(MODEL_INPUT * (MODEL_INPUT + MODEL_INPUT / 2));

    // Scale NV21 first (libyuv NV21Scale)
    libyuv::NV21Scale(
        reinterpret_cast<const uint8_t*>(nv21),  srcWidth,     // Y plane
        reinterpret_cast<const uint8_t*>(nv21) + srcWidth * srcHeight, (srcWidth / 2) * 2,  // UV
        srcWidth, srcHeight,
        scaledNv21.data(), MODEL_INPUT,
        scaledNv21.data() + MODEL_INPUT * MODEL_INPUT, MODEL_INPUT,
        MODEL_INPUT, MODEL_INPUT,
        libyuv::kFilterBilinear
    );

    libyuv::NV21ToRGB24(
        scaledNv21.data(), MODEL_INPUT,
        scaledNv21.data() + MODEL_INPUT * MODEL_INPUT, MODEL_INPUT,
        rgbBuf.data(), MODEL_INPUT * 3,
        MODEL_INPUT, MODEL_INPUT
    );

    env->ReleaseByteArrayElements(nv21Bytes, nv21, JNI_ABORT);

    // ── 2. Normalise to float32 [0,1] and copy to input tensor ────────────
    TfLiteTensor* inputTensor = TfLiteInterpreterGetInputTensor(g_interpreter, 0);
    float* inputData = TfLiteTensorData(inputTensor);
    for (int i = 0; i < MODEL_INPUT * MODEL_INPUT * 3; ++i) {
        inputData[i] = rgbBuf[i] / 255.0f;
    }

    // ── 3. Run inference ───────────────────────────────────────────────────
    if (TfLiteInterpreterInvoke(g_interpreter) != kTfLiteOk) {
        LOGE("Inference failed"); return env->NewFloatArray(0);
    }

    // ── 4. Decode output [1, 25200, 16] — YOLOv8 face format ─────────────
    const TfLiteTensor* outputTensor = TfLiteInterpreterGetOutputTensor(g_interpreter, 0);
    const float* out = static_cast<const float*>(TfLiteTensorData(outputTensor));
    int numAnchors   = 25200;
    int stride       = 16;   // cx,cy,w,h,conf,x1,y1,x2,y2,x3,y3,x4,y4,x5,y5 (landmarks optional)

    std::vector<Detection> detections;
    for (int i = 0; i < numAnchors; ++i) {
        const float* row  = out + i * stride;
        float conf        = row[4];
        if (conf < confThresh) continue;

        float cx = row[0] / MODEL_INPUT;
        float cy = row[1] / MODEL_INPUT;
        float w  = row[2] / MODEL_INPUT;
        float h  = row[3] / MODEL_INPUT;

        detections.push_back({
            cx - w/2.f, cy - h/2.f,
            cx + w/2.f, cy + h/2.f,
            conf
        });
    }

    auto kept = nms(detections, 0.45f);

    // ── 5. Return as flat float array [x1,y1,x2,y2,score, ...] ───────────
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(kept.size() * 5));
    std::vector<float> flat;
    flat.reserve(kept.size() * 5);
    for (const auto& d : kept) {
        flat.push_back(d.x1);
        flat.push_back(d.y1);
        flat.push_back(d.x2);
        flat.push_back(d.y2);
        flat.push_back(d.score);
    }
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(flat.size()), flat.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_autobots_camera_detection_TFLiteFaceDetector_nativeClose(
        JNIEnv*, jobject) {
    if (g_interpreter) { TfLiteInterpreterDelete(g_interpreter); g_interpreter = nullptr; }
    if (g_delegate)    { TfLiteGpuDelegateV2Delete(g_delegate);  g_delegate    = nullptr; }
    if (g_model)       { TfLiteModelDelete(g_model);             g_model       = nullptr; }
    LOGI("TFLite Face Detector closed");
}
```

---

## `TFLiteFaceDetector.kt` (androidMain)

```kotlin
package com.autobots.camera.detection

import android.content.Context
import java.io.File

class TFLiteFaceDetector(
    private val context: Context,
    private val modelFileName: String = "yolov8n_face.tflite",
    private val useGpu: Boolean = true,
    private val confidenceThreshold: Float = 0.45f
) : FaceDetector {

    init {
        System.loadLibrary("autobots_detector")
        val modelBytes = context.assets.open(modelFileName).readBytes()
        check(nativeInit(modelBytes, useGpu)) { "TFLite init failed" }
    }

    override fun detect(imageBytes: ByteArray, width: Int, height: Int): List<FaceDetectionResult> {
        val raw = nativeDetect(imageBytes, width, height, confidenceThreshold)
        // raw = [x1, y1, x2, y2, score, x1, y1, x2, y2, score, ...]
        val results = mutableListOf<FaceDetectionResult>()
        var i = 0
        while (i + 4 < raw.size) {
            results += FaceDetectionResult(
                boundingBox = BoundingBox(
                    left   = raw[i],
                    top    = raw[i + 1],
                    right  = raw[i + 2],
                    bottom = raw[i + 3]
                ),
                confidence = raw[i + 4]
            )
            i += 5
        }
        return results.sortedByDescending { it.confidence }
    }

    override fun close() = nativeClose()

    // JNI declarations
    private external fun nativeInit(modelBytes: ByteArray, useGpu: Boolean): Boolean
    private external fun nativeDetect(nv21: ByteArray, w: Int, h: Int, thresh: Float): FloatArray
    private external fun nativeClose()
}
```

---

## Model File Placement

```
shared/
└── src/androidMain/
    └── assets/
        └── yolov8n_face.tflite    ← place model here (≈6MB)
```

### Exporting YOLOv8-Face to TFLite

```bash
# Python — convert YOLOv8 face model
pip install ultralytics

python3 - <<'EOF'
from ultralytics import YOLO
model = YOLO("yolov8n-face.pt")          # download from derronqi/yolov8-face
model.export(
    format="tflite",
    half=True,        # FP16 → half size, faster on GPU
    imgsz=640,
    nms=False         # we do NMS in C++ for speed control
)
EOF
# Output: yolov8n-face_saved_model/yolov8n-face_float16.tflite
```

---

## Performance Benchmarks (Estimated)

| Device | Delegate | Inference Time |
|--------|----------|---------------|
| Pixel 8 Pro (Tensor G3) | GPU | ~6ms |
| Samsung S24 (Snapdragon 8 Gen 3) | GPU | ~8ms |
| Xiaomi 14 (Snapdragon 8 Gen 3) | GPU | ~9ms |
| Pixel 6a (Tensor G1) | GPU | ~15ms |
| Any device | CPU (4 threads) | ~40–80ms |

GPU delegate recommended → fits comfortably in 33ms frame budget
