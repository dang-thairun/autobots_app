package com.autobots.camera.detection

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Delegate
import java.io.File

/**
 * TFLite [Delegate] backed by QAIRT's QNN delegate, reached through the JNI shim in
 * `src/main/cpp/qnn_delegate_jni.cpp`.
 *
 * QAIRT ships a C API and no Java binding, so `TfLiteQnnDelegateCreate()` cannot be called
 * from Kotlin directly; all this class does is carry the resulting pointer to
 * [getNativeHandle].
 *
 * **Optional by construction.** The QNN libraries are ~96 MB, so a build may well not include
 * them. Every failure path here returns null from [create] with a reason rather than throwing,
 * and the backend then presents itself as unavailable.
 *
 * ### The manifest declaration is not optional
 *
 * `AndroidManifest.xml` must declare `libcdsprpc.so` and `libadsprpc.so` via
 * `<uses-native-library>`. They are vendor libraries listed in
 * `/vendor/etc/public.libraries.txt`, and since API 30 an app's linker namespace hides any
 * such library it has not asked for. Everything here still *appears* to work without it —
 * the delegate object is created and returns a valid handle — and the failure surfaces two
 * layers down as `QnnDsp <E> Failed to load skel, error: 4000`, which TFLite reports only as
 * "Failed to apply delegate". Measured on SM8635: this single declaration is the difference
 * between the NPU backend running at 26 ms and not running at all.
 */
class QnnDelegate private constructor(
    private var handle: Long,
    val backend: Backend,
) : Delegate, AutoCloseable {

    enum class Backend(val nativeValue: Int) {
        /** Adreno GPU through QNN, as opposed to TFLite's own OpenCL delegate. */
        Gpu(1),
        /** Hexagon HTP — the NPU. */
        Htp(2),
    }

    override fun getNativeHandle(): Long = handle

    override fun close() {
        if (handle != 0L) {
            nativeDelete(handle)
            handle = 0L
        }
    }

    companion object {
        private const val TAG = "QnnDelegate"

        /** Loaded lazily: a build without the shim must not take the whole app down. */
        private val shimLoaded: Boolean by lazy {
            runCatching { System.loadLibrary("autobots_qnn") }
                .onFailure { Log.w(TAG, "libautobots_qnn.so not loadable", it) }
                .isSuccess
        }

        /**
         * Why this backend cannot run, or null when it can.
         *
         * Checked before building an interpreter so the UI can grey the option out and name
         * the missing piece, instead of failing at the first frame of a session.
         */
        fun unavailableReason(context: Context): String? {
            if (!shimLoaded) return "JNI shim missing (libautobots_qnn.so)"
            nativeUnavailableReason()?.let { return it }
            val dspDir = runCatching { ensureDspLibraries(context) }.getOrElse {
                return "cannot unpack DSP libraries: ${it.message}"
            }
            if (!File(dspDir, SKEL_LIBRARY).exists()) return "$SKEL_LIBRARY missing from $dspDir"
            return null
        }

        /**
         * @param modelToken identifies the compiled graph in the on-disk cache. Must change
         *   whenever the model does, or a stale graph gets reused.
         * @return null when unavailable — see [unavailableReason].
         */
        fun create(context: Context, backend: Backend, modelToken: String): QnnDelegate? {
            unavailableReason(context)?.let {
                Log.w(TAG, "QNN $backend unavailable: $it")
                return null
            }
            val cacheDir = File(context.cacheDir, "qnn").apply { mkdirs() }
            val handle = nativeCreate(
                backend.nativeValue,
                ensureDspLibraries(context).absolutePath,
                cacheDir.absolutePath,
                modelToken,
            )
            if (handle == 0L) {
                Log.e(TAG, "TfLiteQnnDelegateCreate failed for $backend")
                return null
            }
            Log.i(TAG, "QNN delegate ready: $backend")
            return QnnDelegate(handle, backend)
        }

        /**
         * Unpacks the DSP-side libraries into a directory of their own and returns it.
         *
         * They cannot live in `jniLibs/` beside the CPU-side ones, because two of them —
         * `libQnnSystem.so` above all — exist in **both** an arm64 and a Hexagon build under
         * the same file name. `ADSP_LIBRARY_PATH` pointed at a mixed directory makes the DSP
         * resolve that dependency to the arm64 ELF, and the only symptom is
         *
         *     QnnDsp <E> loadRemoteSymbols failed with err 4000
         *
         * which TFLite surfaces merely as "Failed to apply delegate". Shipping them as assets
         * and unpacking here keeps the two architectures apart by construction.
         *
         * Re-extracted whenever the APK is newer than what was unpacked, so an upgrade cannot
         * leave a stale skel behind.
         */
        private fun ensureDspLibraries(context: Context): File {
            val target = File(context.filesDir, "qnn/dsp").apply { mkdirs() }
            val apkTime = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            for (name in context.assets.list(DSP_ASSET_DIR).orEmpty()) {
                val out = File(target, name)
                if (out.exists() && out.lastModified() >= apkTime) continue
                context.assets.open("$DSP_ASSET_DIR/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                out.setLastModified(apkTime)
            }
            return target
        }

        /** Hexagon v73 — confirmed with `qnn-platform-validator --coreVersion` on SM8635. */
        private const val DSP_ASSET_DIR = "qnn/hexagon-v73"
        private const val SKEL_LIBRARY = "libQnnHtpV73Skel.so"

        @JvmStatic private external fun nativeUnavailableReason(): String?
        @JvmStatic private external fun nativeCreate(
            backend: Int,
            skelLibraryDir: String,
            cacheDir: String,
            modelToken: String,
        ): Long
        @JvmStatic private external fun nativeDelete(handle: Long)
    }
}
