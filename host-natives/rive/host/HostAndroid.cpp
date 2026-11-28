// The Android platform pieces librive-android calls, for the host (build.sh): logging, Bitmap pixel
// access, the API level, native windows, and class lookup from Rive's own threads. Each one is
// written against the simulated device's Java API, through JNI, rather than the NDK library that
// provides it on a device (liblog, libjnigraphics, bionic, libandroid).

#include <jni.h>

#include <android/api-level.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <sys/system_properties.h>

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "helpers/general.hpp"
#include "helpers/jni_resource.hpp"

namespace
{
// The JNIEnv of the calling thread, if it is attached; never attaches one (logging can run on any
// thread, including ones that are tearing down).
JNIEnv* currentEnv()
{
  JNIEnv* env = nullptr;
  if (rive_android::g_JVM == nullptr ||
      rive_android::g_JVM->GetEnv(reinterpret_cast<void**>(&env),
                                  JNI_VERSION_1_6) != JNI_OK)
  {
    return nullptr;
  }
  return env;
}

// Modified UTF-8 is what NewStringUTF accepts; anything outside ASCII is replaced, so a malformed
// byte cannot abort the JVM.
std::string asciiOnly(const char* text)
{
  std::string out(text ? text : "");
  for (char& c : out)
  {
    if (static_cast<unsigned char>(c) >= 0x80 || c == '\0') c = '?';
  }
  return out;
}

jclass g_logClass = nullptr;
jmethodID g_logPrintln = nullptr;
std::mutex g_logMutex;

bool resolveLog(JNIEnv* env)
{
  std::lock_guard<std::mutex> lock(g_logMutex);
  if (g_logPrintln != nullptr) return true;
  jclass local = rive_host_FindClass(env, "android/util/Log");
  if (local == nullptr)
  {
    env->ExceptionClear();
    return false;
  }
  g_logClass = static_cast<jclass>(env->NewGlobalRef(local));
  env->DeleteLocalRef(local);
  g_logPrintln = env->GetStaticMethodID(
      g_logClass,
      "println",
      "(ILjava/lang/String;Ljava/lang/String;)I");
  if (g_logPrintln == nullptr)
  {
    env->ExceptionClear();
    return false;
  }
  return true;
}

int sdkInt()
{
  static int cached = 0;
  if (cached != 0) return cached;
  JNIEnv* env = currentEnv();
  if (env == nullptr || env->ExceptionCheck()) return 0;
  jclass version = rive_host_FindClass(env, "android/os/Build$VERSION");
  if (version == nullptr)
  {
    env->ExceptionClear();
    return 0;
  }
  jfieldID field = env->GetStaticFieldID(version, "SDK_INT", "I");
  if (field != nullptr) cached = env->GetStaticIntField(version, field);
  env->ExceptionClear();
  env->DeleteLocalRef(version);
  return cached;
}

// AndroidBitmap_lockPixels hands out a pointer to the bitmap's pixels. The Java API has no such
// pointer, so a lock copies them out (Bitmap.copyPixelsToBuffer, the same bytes in the same order)
// and an unlock copies a mutable bitmap's back.
struct LockedBitmap
{
  jweak bitmap;
  std::vector<uint8_t> pixels;
};
std::mutex g_bitmapMutex;
std::vector<LockedBitmap*> g_locked;

int callInt(JNIEnv* env, jobject obj, const char* name)
{
  jclass cls = env->GetObjectClass(obj);
  jmethodID method = env->GetMethodID(cls, name, "()I");
  env->DeleteLocalRef(cls);
  if (method == nullptr) return -1;
  return env->CallIntMethod(obj, method);
}

bool callBoolean(JNIEnv* env, jobject obj, const char* name)
{
  jclass cls = env->GetObjectClass(obj);
  jmethodID method = env->GetMethodID(cls, name, "()Z");
  env->DeleteLocalRef(cls);
  if (method == nullptr) return false;
  return env->CallBooleanMethod(obj, method) == JNI_TRUE;
}

bool copyPixels(JNIEnv* env,
                jobject bitmap,
                std::vector<uint8_t>& pixels,
                const char* method)
{
  jobject buffer = env->NewDirectByteBuffer(pixels.data(),
                                            static_cast<jlong>(pixels.size()));
  if (buffer == nullptr) return false;
  jclass cls = env->GetObjectClass(bitmap);
  jmethodID copy =
      env->GetMethodID(cls, method, "(Ljava/nio/Buffer;)V");
  env->DeleteLocalRef(cls);
  if (copy != nullptr) env->CallVoidMethod(bitmap, copy, buffer);
  env->DeleteLocalRef(buffer);
  return copy != nullptr && !env->ExceptionCheck();
}
} // namespace

// Rive finds framework classes with JNIEnv::FindClass on its own worker threads. On a device the
// boot class path has them; in the simulator they live in the sandbox's class loader, which only
// Rive's loader-aware lookup (initialised from the app's ClassLoader in cppInitialize) sees. build.sh
// rewrites every `->FindClass(` in librive-android's sources to this.
extern "C" jclass rive_host_FindClass(JNIEnv* env, const char* name)
{
  return rive_android::FindClass(env, name).release();
}

extern "C" int __android_log_write(int prio, const char* tag, const char* text)
{
  JNIEnv* env = currentEnv();
  if (env != nullptr && !env->ExceptionCheck() && resolveLog(env))
  {
    jstring jTag = env->NewStringUTF(asciiOnly(tag).c_str());
    jstring jText = env->NewStringUTF(asciiOnly(text).c_str());
    if (jTag != nullptr && jText != nullptr)
    {
      int priority = prio < ANDROID_LOG_VERBOSE   ? ANDROID_LOG_VERBOSE
                     : prio > ANDROID_LOG_FATAL ? ANDROID_LOG_FATAL
                                                 : prio;
      // android.util.Log's ASSERT is 7, as ANDROID_LOG_FATAL is.
      env->CallStaticIntMethod(g_logClass, g_logPrintln, priority, jTag, jText);
    }
    if (jTag != nullptr) env->DeleteLocalRef(jTag);
    if (jText != nullptr) env->DeleteLocalRef(jText);
    if (!env->ExceptionCheck()) return 1;
    env->ExceptionClear();
  }
  // No attached thread (or Log failed): stderr. Never stdout, which the simulator's control
  // channel owns.
  fprintf(stderr, "%s: %s\n", tag ? tag : "rive", text ? text : "");
  return 1;
}

extern "C" int __android_log_print(int prio, const char* tag, const char* fmt, ...)
{
  char buffer[1024];
  va_list args;
  va_start(args, fmt);
  vsnprintf(buffer, sizeof(buffer), fmt, args);
  va_end(args);
  return __android_log_write(prio, tag, buffer);
}

extern "C" int android_get_device_api_level(void) { return sdkInt(); }

extern "C" int __system_property_get(const char* name, char* value)
{
  value[0] = '\0';
  if (name != nullptr && strcmp(name, "ro.build.version.sdk") == 0)
  {
    int sdk = sdkInt();
    if (sdk > 0) return snprintf(value, PROP_VALUE_MAX, "%d", sdk);
  }
  return 0;
}

extern "C" int AndroidBitmap_getInfo(JNIEnv* env,
                                     jobject bitmap,
                                     AndroidBitmapInfo* info)
{
  if (env == nullptr || bitmap == nullptr || info == nullptr)
  {
    return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
  }
  int width = callInt(env, bitmap, "getWidth");
  int height = callInt(env, bitmap, "getHeight");
  int rowBytes = callInt(env, bitmap, "getRowBytes");
  if (env->ExceptionCheck()) return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;

  int32_t format = ANDROID_BITMAP_FORMAT_NONE;
  jclass cls = env->GetObjectClass(bitmap);
  jmethodID getConfig = env->GetMethodID(cls,
                                         "getConfig",
                                         "()Landroid/graphics/Bitmap$Config;");
  env->DeleteLocalRef(cls);
  jobject config =
      getConfig != nullptr ? env->CallObjectMethod(bitmap, getConfig) : nullptr;
  if (env->ExceptionCheck()) return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
  if (config != nullptr)
  {
    jclass enumClass = env->GetObjectClass(config);
    jmethodID nameMethod =
        env->GetMethodID(enumClass, "name", "()Ljava/lang/String;");
    env->DeleteLocalRef(enumClass);
    auto jName = static_cast<jstring>(env->CallObjectMethod(config, nameMethod));
    if (jName != nullptr)
    {
      const char* configName = env->GetStringUTFChars(jName, nullptr);
      if (strcmp(configName, "ARGB_8888") == 0)
        format = ANDROID_BITMAP_FORMAT_RGBA_8888;
      else if (strcmp(configName, "RGB_565") == 0)
        format = ANDROID_BITMAP_FORMAT_RGB_565;
      else if (strcmp(configName, "ARGB_4444") == 0)
        format = ANDROID_BITMAP_FORMAT_RGBA_4444;
      else if (strcmp(configName, "ALPHA_8") == 0)
        format = ANDROID_BITMAP_FORMAT_A_8;
      else if (strcmp(configName, "RGBA_F16") == 0)
        format = ANDROID_BITMAP_FORMAT_RGBA_F16;
      else if (strcmp(configName, "RGBA_1010102") == 0)
        format = ANDROID_BITMAP_FORMAT_RGBA_1010102;
      env->ReleaseStringUTFChars(jName, configName);
      env->DeleteLocalRef(jName);
    }
    env->DeleteLocalRef(config);
  }
  // ANDROID_BITMAP_FLAGS_ALPHA_PREMUL (0), _OPAQUE (1), _UNPREMUL (2).
  uint32_t flags = 0;
  if (!callBoolean(env, bitmap, "hasAlpha"))
    flags = 1;
  else if (!callBoolean(env, bitmap, "isPremultiplied"))
    flags = 2;
  if (env->ExceptionCheck()) return ANDROID_BITMAP_RESULT_JNI_EXCEPTION;

  info->width = static_cast<uint32_t>(width);
  info->height = static_cast<uint32_t>(height);
  info->stride = static_cast<uint32_t>(rowBytes);
  info->format = format;
  info->flags = flags;
  return ANDROID_BITMAP_RESULT_SUCCESS;
}

extern "C" int AndroidBitmap_lockPixels(JNIEnv* env,
                                        jobject bitmap,
                                        void** addrPtr)
{
  if (env == nullptr || bitmap == nullptr)
  {
    return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
  }
  AndroidBitmapInfo info;
  int result = AndroidBitmap_getInfo(env, bitmap, &info);
  if (result != ANDROID_BITMAP_RESULT_SUCCESS) return result;

  auto* locked = new LockedBitmap();
  locked->pixels.resize(static_cast<size_t>(info.stride) * info.height);
  if (!copyPixels(env, bitmap, locked->pixels, "copyPixelsToBuffer"))
  {
    delete locked;
    return env->ExceptionCheck() ? ANDROID_BITMAP_RESULT_JNI_EXCEPTION
                                 : ANDROID_BITMAP_RESULT_ALLOCATION_FAILED;
  }
  locked->bitmap = env->NewWeakGlobalRef(bitmap);
  {
    std::lock_guard<std::mutex> lock(g_bitmapMutex);
    g_locked.push_back(locked);
  }
  if (addrPtr != nullptr) *addrPtr = locked->pixels.data();
  return ANDROID_BITMAP_RESULT_SUCCESS;
}

extern "C" int AndroidBitmap_unlockPixels(JNIEnv* env, jobject bitmap)
{
  if (env == nullptr || bitmap == nullptr)
  {
    return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
  }
  LockedBitmap* locked = nullptr;
  {
    std::lock_guard<std::mutex> lock(g_bitmapMutex);
    for (auto it = g_locked.begin(); it != g_locked.end(); ++it)
    {
      if (env->IsSameObject((*it)->bitmap, bitmap))
      {
        locked = *it;
        g_locked.erase(it);
        break;
      }
    }
  }
  if (locked == nullptr) return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
  int result = ANDROID_BITMAP_RESULT_SUCCESS;
  if (callBoolean(env, bitmap, "isMutable") &&
      !copyPixels(env, bitmap, locked->pixels, "copyPixelsFromBuffer"))
  {
    result = ANDROID_BITMAP_RESULT_JNI_EXCEPTION;
  }
  env->DeleteWeakGlobalRef(locked->bitmap);
  delete locked;
  return result;
}

// Native windows back only the GPU renderer, which the host build never starts: no Surface has
// one, so a caller takes its no-window path.
extern "C" ANativeWindow* ANativeWindow_fromSurface(JNIEnv*, jobject) { return nullptr; }
extern "C" void ANativeWindow_acquire(ANativeWindow*) {}
extern "C" void ANativeWindow_release(ANativeWindow*) {}
extern "C" int32_t ANativeWindow_getWidth(ANativeWindow*) { return 0; }
extern "C" int32_t ANativeWindow_getHeight(ANativeWindow*) { return 0; }

// On a device stdout goes nowhere, so the runtime prints there freely (Luau's print, Yoga's logger,
// diagnostics). In the simulator stdout is the control channel, so build.sh links with --wrap for
// each of these and they write to stderr instead.
extern "C"
{
  FILE* __wrap_stdout = nullptr;

  __attribute__((constructor(101))) static void riveHostStdout()
  {
    __wrap_stdout = stderr;
  }

  int __wrap_vprintf(const char* format, va_list args)
  {
    return vfprintf(stderr, format, args);
  }

  int __wrap_printf(const char* format, ...)
  {
    va_list args;
    va_start(args, format);
    int written = vfprintf(stderr, format, args);
    va_end(args);
    return written;
  }

  int __wrap_puts(const char* text)
  {
    if (fputs(text, stderr) < 0) return EOF;
    return fputc('\n', stderr);
  }

  int __wrap_putchar(int c) { return fputc(c, stderr); }
}
