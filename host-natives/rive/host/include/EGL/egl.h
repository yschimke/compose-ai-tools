// Host stand-in for <EGL/egl.h>: glad's EGL, as Rive's renderer uses off Android, whose entry
// points glad loads from the host's libEGL at run time (host/HostEgl.cpp), so librive-android links
// against no EGL library.
//
// librive-android's own EGL calls go to host/HostEgl.cpp instead (the rive_host_ functions below),
// which gives the tokens the simulator's EGL14 hands out (SimulatorShadowEGL14) real objects on the
// host's EGL and reads each frame back for the simulator. HostEgl.cpp defines RIVE_HOST_EGL_IMPL to
// call glad's directly.
#pragma once

#include <glad/egl.h>

#ifndef RIVE_HOST_EGL_IMPL
#ifdef __cplusplus
extern "C" {
#endif
EGLBoolean rive_host_eglMakeCurrent(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
EGLBoolean rive_host_eglSwapBuffers(EGLDisplay, EGLSurface);
EGLContext rive_host_eglGetCurrentContext(void);
EGLSurface rive_host_eglGetCurrentSurface(EGLint);
EGLBoolean rive_host_eglChooseConfig(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*);
EGLSurface rive_host_eglCreatePbufferSurface(EGLDisplay, EGLConfig, const EGLint*);
EGLBoolean rive_host_eglDestroySurface(EGLDisplay, EGLSurface);
EGLBoolean rive_host_eglQueryContext(EGLDisplay, EGLContext, EGLint, EGLint*);
EGLint rive_host_eglGetError(void);
// Whether the host has an EGL to render with, and the size the app gave a window surface.
bool rive_host_egl_available(void);
void rive_host_egl_window_size(EGLSurface surface, int width, int height);
#ifdef __cplusplus
}
#endif
#undef eglMakeCurrent
#define eglMakeCurrent rive_host_eglMakeCurrent
#undef eglSwapBuffers
#define eglSwapBuffers rive_host_eglSwapBuffers
#undef eglGetCurrentContext
#define eglGetCurrentContext rive_host_eglGetCurrentContext
#undef eglGetCurrentSurface
#define eglGetCurrentSurface rive_host_eglGetCurrentSurface
#undef eglChooseConfig
#define eglChooseConfig rive_host_eglChooseConfig
#undef eglCreatePbufferSurface
#define eglCreatePbufferSurface rive_host_eglCreatePbufferSurface
#undef eglDestroySurface
#define eglDestroySurface rive_host_eglDestroySurface
#undef eglQueryContext
#define eglQueryContext rive_host_eglQueryContext
#undef eglGetError
#define eglGetError rive_host_eglGetError
#endif
