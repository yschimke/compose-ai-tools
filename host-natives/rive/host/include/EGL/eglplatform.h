// Host stand-in for <EGL/eglplatform.h>: glad's, with Android's native types, which librive-android
// passes to EGL (an ANativeWindow* window). The ANDROID macro selects them and is gone afterwards.
#pragma once

#ifndef ANDROID
#define ANDROID 1
#define RIVE_HOST_EGLPLATFORM_ANDROID 1
#endif
#include_next <EGL/eglplatform.h>
#ifdef RIVE_HOST_EGLPLATFORM_ANDROID
#undef ANDROID
#undef RIVE_HOST_EGLPLATFORM_ANDROID
#endif
