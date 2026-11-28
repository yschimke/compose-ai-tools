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
// pointer, so a lock copies them out and an unlock copies a mutable bitmap's back. Through colour
// ints (Bitmap.getPixels), not copyPixelsToBuffer: under Robolectric's native graphics a bitmap's
// bytes are in the host's order (BGRA), where the NDK's RGBA_8888 is R, G, B, A in memory, and
// premultiplied when the bitmap is.
struct LockedBitmap
{
  jweak bitmap;
  std::vector<uint8_t> pixels;
  uint32_t width;
  uint32_t height;
  uint32_t stride;
  bool premultiplied;
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

// The bitmap's colours into [locked]'s RGBA bytes.
bool readPixels(JNIEnv* env, jobject bitmap, LockedBitmap* locked)
{
  jclass cls = env->GetObjectClass(bitmap);
  jmethodID getPixels = env->GetMethodID(cls, "getPixels", "([IIIIIII)V");
  env->DeleteLocalRef(cls);
  if (getPixels == nullptr) return false;
  const jsize count = static_cast<jsize>(locked->width * locked->height);
  jintArray colors = env->NewIntArray(count);
  if (colors == nullptr) return false;
  env->CallVoidMethod(bitmap,
                      getPixels,
                      colors,
                      0,
                      static_cast<jint>(locked->width),
                      0,
                      0,
                      static_cast<jint>(locked->width),
                      static_cast<jint>(locked->height));
  if (env->ExceptionCheck())
  {
    env->DeleteLocalRef(colors);
    return false;
  }
  jint* values = env->GetIntArrayElements(colors, nullptr);
  for (uint32_t y = 0; y < locked->height; ++y)
  {
    uint8_t* row = &locked->pixels[static_cast<size_t>(y) * locked->stride];
    for (uint32_t x = 0; x < locked->width; ++x)
    {
      const uint32_t c = static_cast<uint32_t>(values[y * locked->width + x]);
      uint32_t a = c >> 24, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
      if (locked->premultiplied && a != 255)
      {
        r = r * a / 255;
        g = g * a / 255;
        b = b * a / 255;
      }
      row[x * 4 + 0] = static_cast<uint8_t>(r);
      row[x * 4 + 1] = static_cast<uint8_t>(g);
      row[x * 4 + 2] = static_cast<uint8_t>(b);
      row[x * 4 + 3] = static_cast<uint8_t>(a);
    }
  }
  env->ReleaseIntArrayElements(colors, values, JNI_ABORT);
  env->DeleteLocalRef(colors);
  return true;
}

// [locked]'s RGBA bytes back into the bitmap's colours.
bool writePixels(JNIEnv* env, jobject bitmap, const LockedBitmap* locked)
{
  jclass cls = env->GetObjectClass(bitmap);
  jmethodID setPixels = env->GetMethodID(cls, "setPixels", "([IIIIIII)V");
  env->DeleteLocalRef(cls);
  if (setPixels == nullptr) return false;
  const jsize count = static_cast<jsize>(locked->width * locked->height);
  std::vector<jint> values(static_cast<size_t>(count));
  for (uint32_t y = 0; y < locked->height; ++y)
  {
    const uint8_t* row = &locked->pixels[static_cast<size_t>(y) * locked->stride];
    for (uint32_t x = 0; x < locked->width; ++x)
    {
      uint32_t r = row[x * 4 + 0], g = row[x * 4 + 1], b = row[x * 4 + 2], a = row[x * 4 + 3];
      if (locked->premultiplied && a != 0 && a != 255)
      {
        r = r * 255 / a > 255 ? 255 : r * 255 / a;
        g = g * 255 / a > 255 ? 255 : g * 255 / a;
        b = b * 255 / a > 255 ? 255 : b * 255 / a;
      }
      values[y * locked->width + x] = static_cast<jint>((a << 24) | (r << 16) | (g << 8) | b);
    }
  }
  jintArray colors = env->NewIntArray(count);
  if (colors == nullptr) return false;
  env->SetIntArrayRegion(colors, 0, count, values.data());
  env->CallVoidMethod(bitmap,
                      setPixels,
                      colors,
                      0,
                      static_cast<jint>(locked->width),
                      0,
                      0,
                      static_cast<jint>(locked->width),
                      static_cast<jint>(locked->height));
  env->DeleteLocalRef(colors);
  return !env->ExceptionCheck();
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
  // The stride of what AndroidBitmap_lockPixels hands out: packed rows.
  info->stride = format == ANDROID_BITMAP_FORMAT_RGBA_8888 ? static_cast<uint32_t>(width) * 4
                                                           : static_cast<uint32_t>(rowBytes);
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

  if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
  {
    // Only ARGB_8888 is read through colour ints; nothing here locks another config.
    return ANDROID_BITMAP_RESULT_BAD_PARAMETER;
  }
  auto* locked = new LockedBitmap();
  locked->width = info.width;
  locked->height = info.height;
  locked->stride = info.width * 4;
  // ANDROID_BITMAP_FLAGS_ALPHA_UNPREMUL is 2.
  locked->premultiplied = (info.flags & 2) == 0;
  locked->pixels.resize(static_cast<size_t>(locked->stride) * info.height);
  if (!readPixels(env, bitmap, locked))
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
  if (callBoolean(env, bitmap, "isMutable") && !writePixels(env, bitmap, locked))
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

  // Before libstdc++'s standard streams (its static ios_base::Init has init priority 90): the
  // libstdc++ linked in here is wrapped too, so std::cout writes through this pointer, and builds
  // over whatever it holds when it starts. 50 is in the range reserved for the implementation,
  // which this is standing in for.
  __attribute__((constructor(50))) static void riveHostStdout()
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
