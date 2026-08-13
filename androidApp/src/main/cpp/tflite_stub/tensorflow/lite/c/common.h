// Minimal stand-in for TensorFlow Lite's C header.
//
// QAIRT's QnnTFLiteDelegate.h includes "tensorflow/lite/c/common.h", but the QAIRT SDK
// does not ship it and the TFLite Android AAR carries no headers. Pulling the real
// TensorFlow source tree in for this would be absurd: the QNN header references exactly
// two TFLite names, `TfLiteDelegate` and `TfLiteRegistration`, and only ever as pointers
// or in comments. Incomplete types are all a pointer needs.
//
// The handle crosses into Kotlin as an opaque jlong and comes back to
// TfLiteQnnDelegateDelete untouched, so nothing here ever dereferences it.
#pragma once

typedef struct TfLiteDelegate TfLiteDelegate;
typedef struct TfLiteRegistration TfLiteRegistration;
