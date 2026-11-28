// Force-included into every librive-android source on the host (build.sh). On Android these headers
// arrive transitively (through the NDK's libc++ and bionic's <stdlib.h>); on the host they do not.
#pragma once

#include <sys/system_properties.h>

// gettid(), which bionic has and glibc only wraps from 2.30; the manylinux_2_28 build is older.
#if defined(__linux__)
#include <unistd.h>
#if defined(__GLIBC__) && (__GLIBC__ < 2 || (__GLIBC__ == 2 && __GLIBC_MINOR__ < 30))
#include <sys/syscall.h>
static inline pid_t gettid(void) { return (pid_t)syscall(SYS_gettid); }
#endif
#endif

#ifdef __cplusplus
#include <algorithm>
#include <cstring>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#endif

#include <jni.h>
#ifdef __cplusplus
extern "C" {
#endif
// Every `->FindClass(` in librive-android's sources is rewritten to this (build.sh): see
// HostAndroid.cpp.
jclass rive_host_FindClass(JNIEnv* env, const char* name);
#ifdef __cplusplus
}
#endif
