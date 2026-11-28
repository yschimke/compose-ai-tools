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

// Thread affinity, which librive-android's helpers/thread.cpp sets with Linux's API; macOS has no
// equivalent, and affinity is only a scheduling hint, so these record nothing and pin nothing.
#if defined(__APPLE__)
#include <pthread.h>
#include <sys/types.h>
#include <unistd.h>
typedef struct { unsigned long long bits; } cpu_set_t;
#define CPU_ZERO(set) ((set)->bits = 0)
#define CPU_SET(cpu, set) ((set)->bits |= 1ULL << (cpu))
#define CPU_ISSET(cpu, set) (((set)->bits >> (cpu)) & 1ULL)
static inline pid_t gettid(void)
{
  uint64_t id = 0;
  pthread_threadid_np(NULL, &id);
  return (pid_t)id;
}
// Every CPU, as if unpinned.
static inline int sched_getaffinity(pid_t, size_t, cpu_set_t* set)
{
  long count = sysconf(_SC_NPROCESSORS_ONLN);
  set->bits = count >= 64 ? ~0ULL : ((1ULL << (count > 0 ? count : 1)) - 1);
  return 0;
}
static inline int sched_setaffinity(pid_t, size_t, const cpu_set_t*) { return 0; }
#ifdef __cplusplus
// Linux's pthread_setname_np(thread, name); macOS's names only the calling thread, which is the
// only one librive-android names (helpers/worker_thread.hpp, with pthread_self()).
static inline int pthread_setname_np(pthread_t thread, const char* name)
{
  return pthread_equal(thread, pthread_self()) ? pthread_setname_np(name) : 0;
}
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
