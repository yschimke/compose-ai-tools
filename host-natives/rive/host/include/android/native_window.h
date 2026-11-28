// Host stand-in for the NDK's <android/native_window.h>. Native windows back only the GPU (Rive)
// renderer, which the host build never starts (see build.sh), so this is declarations only;
// host/HostAndroid.cpp's definitions have no window to hand out.
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

struct ANativeWindow;
typedef struct ANativeWindow ANativeWindow;

void ANativeWindow_acquire(ANativeWindow* window);
void ANativeWindow_release(ANativeWindow* window);
int32_t ANativeWindow_getWidth(ANativeWindow* window);
int32_t ANativeWindow_getHeight(ANativeWindow* window);

#ifdef __cplusplus
}
#endif
