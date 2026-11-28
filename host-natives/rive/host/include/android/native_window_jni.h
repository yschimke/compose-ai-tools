// Host stand-in for the NDK's <android/native_window_jni.h>; see native_window.h.
#pragma once

#include <jni.h>

#include "android/native_window.h"

#ifdef __cplusplus
extern "C" {
#endif

ANativeWindow* ANativeWindow_fromSurface(JNIEnv* env, jobject surface);

#ifdef __cplusplus
}
#endif
