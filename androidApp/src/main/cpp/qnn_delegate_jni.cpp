// JNI shim over QAIRT's QNN TFLite delegate.
//
// QAIRT ships a C API and no Java binding, so `TfLiteQnnDelegateCreate()` is unreachable
// from Kotlin. This file exists only to call it and hand the resulting pointer back as an
// opaque handle that `org.tensorflow.lite.Delegate` can return from `getNativeHandle()`.
//
// libQnnTFLiteDelegate.so is opened with dlopen rather than linked. The QNN libraries are
// ~96 MB and optional: a build without them, or a device that cannot load them, must
// degrade to "this backend is unavailable" rather than fail to start the app. dlopen makes
// that the natural outcome instead of a special case.

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string>

#define LOG_TAG "QnnDelegate"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef HAVE_QNN_HEADERS
#include "QNN/TFLiteDelegate/QnnTFLiteDelegate.h"

namespace {

using OptionsDefaultFn = TfLiteQnnDelegateOptions (*)();
using CreateFn = TfLiteDelegate *(*)(const TfLiteQnnDelegateOptions *);
using DeleteFn = void (*)(TfLiteDelegate *);

struct QnnApi {
    void *handle = nullptr;
    OptionsDefaultFn optionsDefault = nullptr;
    CreateFn create = nullptr;
    DeleteFn destroy = nullptr;
    std::string error;

    bool ok() const { return create != nullptr && optionsDefault != nullptr; }
};

/// Resolved once per process. A failure here is a permanent property of the build or the
/// device, so there is nothing to gain from retrying on every frame.
QnnApi &api() {
    static QnnApi instance = [] {
        QnnApi a;
        a.handle = dlopen("libQnnTFLiteDelegate.so", RTLD_NOW | RTLD_LOCAL);
        if (a.handle == nullptr) {
            const char *err = dlerror();
            a.error = err != nullptr ? err : "dlopen failed";
            LOGE("libQnnTFLiteDelegate.so not loadable: %s", a.error.c_str());
            return a;
        }
        a.optionsDefault =
            reinterpret_cast<OptionsDefaultFn>(dlsym(a.handle, "TfLiteQnnDelegateOptionsDefault"));
        a.create = reinterpret_cast<CreateFn>(dlsym(a.handle, "TfLiteQnnDelegateCreate"));
        a.destroy = reinterpret_cast<DeleteFn>(dlsym(a.handle, "TfLiteQnnDelegateDelete"));
        if (!a.ok()) {
            a.error = "libQnnTFLiteDelegate.so loaded but symbols missing";
            LOGE("%s", a.error.c_str());
        } else {
            LOGI("QNN TFLite delegate resolved");
        }
        return a;
    }();
    return instance;
}

std::string toStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

}  // namespace
#endif  // HAVE_QNN_HEADERS

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_autobots_camera_detection_QnnDelegate_nativeUnavailableReason(JNIEnv *env, jclass) {
#ifndef HAVE_QNN_HEADERS
    return env->NewStringUTF("built without the QAIRT SDK headers");
#else
    QnnApi &a = api();
    if (a.ok()) return nullptr;
    return env->NewStringUTF(a.error.c_str());
#endif
}

/// @param backend 1 = GPU (Adreno), 2 = HTP (Hexagon NPU) — TfLiteQnnDelegateBackendType.
/// @param skelLibraryDir directory holding libQnnHtpV*Skel.so. The HTP backend hands this
///        path to the DSP, which is why the libraries must be extracted to disk
///        (`useLegacyPackaging = true`) rather than left compressed inside the APK.
/// @param cacheDir where to keep the compiled graph between runs; empty disables caching.
/// @return the delegate pointer as an opaque handle, or 0 on failure.
JNIEXPORT jlong JNICALL Java_com_autobots_camera_detection_QnnDelegate_nativeCreate(
    JNIEnv *env, jclass, jint backend, jstring skelLibraryDir, jstring cacheDir,
    jstring modelToken) {
#ifndef HAVE_QNN_HEADERS
    (void)env; (void)backend; (void)skelLibraryDir; (void)cacheDir; (void)modelToken;
    return 0;
#else
    // The option struct holds borrowed `const char*`; these have to outlive the create call.
    const std::string skelDir = toStdString(env, skelLibraryDir);
    const std::string cache = toStdString(env, cacheDir);
    const std::string token = toStdString(env, modelToken);

    // ADSP_LIBRARY_PATH has to be in the environment **before libQnnTFLiteDelegate.so is
    // loaded**, because the FastRPC loader it pulls in reads the variable while initialising.
    // Setting it afterwards is silently too late, and the only evidence is
    //
    //     QnnDsp <E> loadRemoteSymbols failed with err 4000
    //     QnnDsp <E> Failed to load skel, error: 4000
    //
    // which TFLite reports merely as "Failed to apply delegate". Hence: setenv first, then
    // api(), which is what performs the dlopen.
    //
    // [skelDir] must also contain *only* Hexagon builds. Two of the QNN libraries —
    // `libQnnSystem.so` above all — ship under the same file name for arm64 and for Hexagon,
    // so a directory holding both makes the DSP resolve the dependency to the arm64 ELF and
    // fail with the same 4000. Verified with `qnn-platform-validator --testBackend`: a clean
    // directory passes, the identical skel beside the arm64 libraries fails.
    if (!skelDir.empty()) {
        const std::string adspPath = skelDir +
                                     ";/vendor/lib/rfsa/adsp"
                                     ";/vendor/dsp/cdsp"
                                     ";/system/lib/rfsa/adsp"
                                     ";/dsp";
        setenv("ADSP_LIBRARY_PATH", adspPath.c_str(), 1);
        LOGI("ADSP_LIBRARY_PATH=%s", adspPath.c_str());
    }

    QnnApi &a = api();
    if (!a.ok()) return 0;

    TfLiteQnnDelegateOptions options = a.optionsDefault();
    options.backend_type = static_cast<TfLiteQnnDelegateBackendType>(backend);
    if (!skelDir.empty()) options.skel_library_dir = skelDir.c_str();
    if (!cache.empty() && !token.empty()) {
        options.cache_dir = cache.c_str();
        options.model_token = token.c_str();
    }

    TfLiteDelegate *delegate = a.create(&options);
    if (delegate == nullptr) {
        LOGE("TfLiteQnnDelegateCreate returned null for backend %d", backend);
        return 0;
    }
    LOGI("QNN delegate created (backend=%d, skelDir=%s)", backend,
         skelDir.empty() ? "<default>" : skelDir.c_str());
    return reinterpret_cast<jlong>(delegate);
#endif
}

JNIEXPORT void JNICALL Java_com_autobots_camera_detection_QnnDelegate_nativeDelete(JNIEnv *,
                                                                                  jclass,
                                                                                  jlong handle) {
#ifdef HAVE_QNN_HEADERS
    QnnApi &a = api();
    if (handle != 0 && a.destroy != nullptr) {
        a.destroy(reinterpret_cast<TfLiteDelegate *>(handle));
    }
#else
    (void)handle;
#endif
}

}  // extern "C"
