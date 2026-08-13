cmake_minimum_required(VERSION 3.22.1)
project(autobots_qnn)

# Headers come straight from the QAIRT SDK checkout rather than being copied into the
# repo — they are Qualcomm's, and vendoring them would mean committing files we cannot
# redistribute. If the SDK is missing the shim still builds and every entry point
# reports the backend as unavailable, which is exactly how a missing .so behaves.
set(QAIRT_INCLUDE "${CMAKE_SOURCE_DIR}/../../../../tools/qairt/2.49.0.260730/include")

add_library(autobots_qnn SHARED qnn_delegate_jni.cpp)

if(EXISTS "${QAIRT_INCLUDE}/QNN/TFLiteDelegate/QnnTFLiteDelegate.h")
    # tflite_stub supplies the two forward declarations QAIRT's header expects from
    # "tensorflow/lite/c/common.h", which neither the SDK nor the TFLite AAR ships.
    target_include_directories(autobots_qnn PRIVATE
        "${QAIRT_INCLUDE}"
        "${CMAKE_SOURCE_DIR}/tflite_stub")
    target_compile_definitions(autobots_qnn PRIVATE HAVE_QNN_HEADERS=1)
    message(STATUS "autobots_qnn: QAIRT headers found, QNN delegate enabled")
else()
    message(WARNING "autobots_qnn: QAIRT headers not found at ${QAIRT_INCLUDE} — "
                    "building stub; the NPU backend will report itself unavailable")
endif()

# libQnnTFLiteDelegate.so is opened with dlopen at runtime, never linked. A device or a
# build without it then degrades to "NPU unavailable" instead of failing to load the
# whole app.
target_link_libraries(autobots_qnn log dl)
