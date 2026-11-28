// Force-included into every librive-android source on the host (build.sh). On Android these headers
// arrive transitively (through the NDK's libc++ and bionic's <stdlib.h>); on the host they do not.
#pragma once

#include <sys/system_properties.h>

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
