// EGL for librive-android's GPU render context (Rive's Compose API) on the host (build.sh).
//
// The app's Kotlin creates its EGL display, context and surfaces through android.opengl.EGL14,
// which the simulator answers with tagged tokens (SimulatorShadowEGL14) and no GPU; the native
// render context then makes them current, renders with OpenGL ES and swaps. host/include/EGL/egl.h
// sends librive-android's own EGL calls here, which:
//   - give each token a real object on the host's EGL, loaded at run time through glad (Mesa's
//     libEGL and libGLESv2 on Linux, a GPU's, or none: then the render context fails to start, as
//     on a device without OpenGL ES): one headless display and config, a context per context
//     token, and an offscreen (pbuffer) surface per surface token, sized as the app's window;
//   - load OpenGL ES once a context is current;
//   - after each swap of a window surface, read the frame back and hand it to
//     SimulatorShadowEGL14.present, which draws it into the window's android.view.Surface;
//   - pass a handle that is not a token straight to the host's EGL.

#define RIVE_HOST_EGL_IMPL 1
#include <EGL/egl.h>

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <map>
#include <mutex>
#include <vector>

#include "glad_custom.h"
#include "helpers/general.hpp"
#include <android/log.h>

namespace
{

constexpr auto* TAG = "RiveHost/EGL";

// EGL_MESA_platform_surfaceless, which glad's EGL headers leave out.
constexpr EGLenum PLATFORM_SURFACELESS_MESA = 0x31DD;

// SimulatorShadowEGL14.TAG and its kinds.
constexpr uint64_t TOKEN_TAG = 0x51D0ULL << 48;
constexpr uint64_t TAG_MASK = 0xFFFFULL << 48;
constexpr uint64_t KIND_CONTEXT = 3;
constexpr uint64_t KIND_SURFACE = 4;

bool isToken(const void* handle)
{
  return (reinterpret_cast<uint64_t>(handle) & TAG_MASK) == TOKEN_TAG;
}
uint64_t kind(const void* handle)
{
  return (reinterpret_cast<uint64_t>(handle) >> 32) & 0xFFFF;
}

struct Surface
{
  EGLSurface real = EGL_NO_SURFACE;
  int width = 1;
  int height = 1;
  bool window = false;
};

std::mutex g_mutex;
bool g_tried = false;
bool g_available = false;
bool g_glLoaded = false;
EGLDisplay g_display = EGL_NO_DISPLAY;
EGLConfig g_config = nullptr;
std::map<const void*, EGLContext> g_contexts;
std::map<const void*, Surface> g_surfaces;
// Sizes the app gave its window surfaces (RenderContextGL.cppCreateSurface), by token.
std::map<const void*, std::pair<int, int>> g_windowSizes;

// What the thread's tokens are, for eglGetCurrent*.
thread_local EGLContext t_context = EGL_NO_CONTEXT;
thread_local EGLSurface t_draw = EGL_NO_SURFACE;
thread_local EGLSurface t_read = EGL_NO_SURFACE;

// Hold g_mutex.
bool initLocked()
{
  if (g_tried) return g_available;
  g_tried = true;
  if (gladLoaderLoadEGL(nullptr) == 0)
  {
    __android_log_print(ANDROID_LOG_INFO, TAG, "No EGL on this host: the GPU renderer is unavailable.");
    return false;
  }
  // A headless display: Mesa's surfaceless platform where there is one, else the default.
  const char* clientExtensions = glad_eglQueryString != nullptr
                                     ? glad_eglQueryString(EGL_NO_DISPLAY, EGL_EXTENSIONS)
                                     : nullptr;
  if (clientExtensions != nullptr &&
      strstr(clientExtensions, "EGL_MESA_platform_surfaceless") != nullptr &&
      strstr(clientExtensions, "EGL_EXT_platform_base") != nullptr)
  {
    // EGL_EXT_platform_base's entry point: glad loads only the core of the version the
    // display-less query reports (1.0 here), so eglGetPlatformDisplay is not loaded yet.
    using GetPlatformDisplay = EGLDisplay (*)(EGLenum, void*, const EGLint*);
    auto getPlatformDisplay = reinterpret_cast<GetPlatformDisplay>(
        glad_eglGetProcAddress("eglGetPlatformDisplayEXT"));
    if (getPlatformDisplay != nullptr)
    {
      g_display = getPlatformDisplay(PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    }
  }
  if (g_display == EGL_NO_DISPLAY)
  {
    g_display = glad_eglGetDisplay(EGL_DEFAULT_DISPLAY);
  }
  EGLint major = 0, minor = 0;
  if (g_display == EGL_NO_DISPLAY ||
      !glad_eglInitialize(g_display, &major, &minor))
  {
    __android_log_print(ANDROID_LOG_INFO, TAG, "The host's EGL has no display: the GPU renderer is unavailable.");
    return false;
  }
  // The display's own entry points (and extensions) too.
  gladLoaderLoadEGL(g_display);
  glad_eglBindAPI(EGL_OPENGL_ES_API);
  const EGLint attributes[] = {EGL_SURFACE_TYPE,
                               EGL_PBUFFER_BIT,
                               EGL_RENDERABLE_TYPE,
                               EGL_OPENGL_ES3_BIT,
                               EGL_RED_SIZE,
                               8,
                               EGL_GREEN_SIZE,
                               8,
                               EGL_BLUE_SIZE,
                               8,
                               EGL_ALPHA_SIZE,
                               8,
                               EGL_STENCIL_SIZE,
                               8,
                               EGL_NONE};
  EGLint count = 0;
  if (!glad_eglChooseConfig(g_display, attributes, &g_config, 1, &count) ||
      count < 1)
  {
    __android_log_print(ANDROID_LOG_INFO, TAG, "The host's EGL has no OpenGL ES 3 config: the GPU renderer is unavailable.");
    return false;
  }
  __android_log_print(ANDROID_LOG_INFO, TAG, "Host EGL %d.%d: rendering with %s",
           major,
           minor,
           glad_eglQueryString(g_display, EGL_VENDOR));
  g_available = true;
  return true;
}

// Hold g_mutex.
EGLContext realContext(EGLContext token)
{
  if (token == EGL_NO_CONTEXT || !isToken(token)) return token;
  auto found = g_contexts.find(token);
  if (found != g_contexts.end()) return found->second;
  const EGLint attributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
  EGLContext real = glad_eglCreateContext(g_display, g_config, EGL_NO_CONTEXT, attributes);
  if (real == EGL_NO_CONTEXT) return EGL_NO_CONTEXT;
  g_contexts[token] = real;
  return real;
}

// Hold g_mutex. A pbuffer at the token's size, replaced when the app's window size changes.
EGLSurface realSurface(EGLSurface token)
{
  if (token == EGL_NO_SURFACE || !isToken(token)) return token;
  Surface& surface = g_surfaces[token];
  auto size = g_windowSizes.find(token);
  if (size != g_windowSizes.end())
  {
    surface.window = true;
    if (surface.real != EGL_NO_SURFACE &&
        (surface.width != size->second.first || surface.height != size->second.second))
    {
      glad_eglDestroySurface(g_display, surface.real);
      surface.real = EGL_NO_SURFACE;
    }
    surface.width = size->second.first;
    surface.height = size->second.second;
  }
  if (surface.real == EGL_NO_SURFACE)
  {
    const EGLint attributes[] = {EGL_WIDTH, surface.width, EGL_HEIGHT, surface.height, EGL_NONE};
    surface.real = glad_eglCreatePbufferSurface(g_display, g_config, attributes);
  }
  return surface.real;
}

// The current token for a real handle, or the handle itself.
template <typename Handle, typename Map>
Handle tokenFor(Handle real, const Map& map)
{
  for (const auto& entry : map)
  {
    if (entry.second == real) return const_cast<Handle>(entry.first);
  }
  return real;
}

// SimulatorShadowEGL14.present, found on first use through the app's class loader.
jclass g_presentClass = nullptr;
jmethodID g_present = nullptr;

bool present(EGLSurface token, int width, int height)
{
  JNIEnv* env = rive_android::GetJNIEnv();
  if (env == nullptr) return false;
  if (g_present == nullptr)
  {
    jclass local = rive_host_FindClass(
        env, "ee/schimke/composeai/simulator/shadows/SimulatorShadowEGL14");
    if (local == nullptr)
    {
      env->ExceptionClear();
      __android_log_print(ANDROID_LOG_ERROR, TAG, "No SimulatorShadowEGL14 to present frames to.");
      return false;
    }
    g_presentClass = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_present = env->GetStaticMethodID(g_presentClass,
                                       "present",
                                       "(JLjava/nio/ByteBuffer;II)Z");
    if (g_present == nullptr)
    {
      env->ExceptionClear();
      return false;
    }
  }
  // The frame, bottom row first as OpenGL reads it, then flipped to top row first.
  std::vector<uint8_t> pixels(static_cast<size_t>(width) * height * 4);
  GLint framebuffer = 0;
  glGetIntegerv(GL_FRAMEBUFFER_BINDING, &framebuffer);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);
  glPixelStorei(GL_PACK_ALIGNMENT, 4);
  glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
  glBindFramebuffer(GL_FRAMEBUFFER, static_cast<GLuint>(framebuffer));
  const size_t row = static_cast<size_t>(width) * 4;
  std::vector<uint8_t> flipped(pixels.size());
  for (int y = 0; y < height; ++y)
  {
    memcpy(&flipped[static_cast<size_t>(y) * row],
           &pixels[static_cast<size_t>(height - 1 - y) * row],
           row);
  }
  jobject buffer = env->NewDirectByteBuffer(flipped.data(), static_cast<jlong>(flipped.size()));
  jboolean shown = env->CallStaticBooleanMethod(g_presentClass,
                                                g_present,
                                                reinterpret_cast<jlong>(token),
                                                buffer,
                                                width,
                                                height);
  env->DeleteLocalRef(buffer);
  if (env->ExceptionCheck())
  {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return false;
  }
  return shown == JNI_TRUE;
}
} // namespace

extern "C"
{
  // Whether the host has an EGL to render with (RenderContextGL.cppConstructor checks).
  bool rive_host_egl_available()
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    return initLocked();
  }

  // The size the app gave a window surface token (RenderContextGL.cppCreateSurface).
  void rive_host_egl_window_size(EGLSurface token, int width, int height)
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_windowSizes[token] = {width, height};
  }

  EGLBoolean rive_host_eglMakeCurrent(EGLDisplay dpy,
                                      EGLSurface draw,
                                      EGLSurface read,
                                      EGLContext ctx)
  {
    std::unique_lock<std::mutex> lock(g_mutex);
    if (!isToken(dpy))
    {
      return glad_eglMakeCurrent != nullptr ? glad_eglMakeCurrent(dpy, draw, read, ctx)
                                            : EGL_FALSE;
    }
    if (!initLocked()) return EGL_FALSE;
    EGLContext realCtx = realContext(ctx);
    EGLSurface realDraw = realSurface(draw);
    EGLSurface realRead = read == draw ? realDraw : realSurface(read);
    lock.unlock();
    if (!glad_eglMakeCurrent(g_display, realDraw, realRead, realCtx)) return EGL_FALSE;
    t_context = ctx;
    t_draw = draw;
    t_read = read;
    if (realCtx != EGL_NO_CONTEXT && !g_glLoaded)
    {
      std::lock_guard<std::mutex> relock(g_mutex);
      if (!g_glLoaded)
      {
        g_glLoaded = gladLoadCustomLoader(
                         reinterpret_cast<GLADloadfunc>(glad_eglGetProcAddress)) != 0;
        __android_log_print(ANDROID_LOG_INFO, TAG,
                 "OpenGL ES %s, %s",
                 glGetString(GL_VERSION),
                 glGetString(GL_RENDERER));
      }
    }
    return EGL_TRUE;
  }

  EGLBoolean rive_host_eglSwapBuffers(EGLDisplay dpy, EGLSurface surface)
  {
    if (!isToken(dpy))
    {
      return glad_eglSwapBuffers != nullptr ? glad_eglSwapBuffers(dpy, surface) : EGL_FALSE;
    }
    int width = 0, height = 0;
    bool window = false;
    {
      std::lock_guard<std::mutex> lock(g_mutex);
      auto found = g_surfaces.find(surface);
      if (found == g_surfaces.end()) return EGL_FALSE;
      window = found->second.window;
      width = found->second.width;
      height = found->second.height;
    }
    if (window && g_glLoaded) present(surface, width, height);
    return EGL_TRUE;
  }

  EGLContext rive_host_eglGetCurrentContext()
  {
    if (t_context != EGL_NO_CONTEXT) return t_context;
    return glad_eglGetCurrentContext != nullptr ? glad_eglGetCurrentContext() : EGL_NO_CONTEXT;
  }

  EGLSurface rive_host_eglGetCurrentSurface(EGLint readdraw)
  {
    if (t_context != EGL_NO_CONTEXT) return readdraw == EGL_READ ? t_read : t_draw;
    return glad_eglGetCurrentSurface != nullptr ? glad_eglGetCurrentSurface(readdraw)
                                                : EGL_NO_SURFACE;
  }

  EGLBoolean rive_host_eglChooseConfig(EGLDisplay dpy,
                                       const EGLint* attributes,
                                       EGLConfig* configs,
                                       EGLint size,
                                       EGLint* count)
  {
    if (!isToken(dpy))
    {
      return glad_eglChooseConfig != nullptr
                 ? glad_eglChooseConfig(dpy, attributes, configs, size, count)
                 : EGL_FALSE;
    }
    // The simulator's one config token (SimulatorShadowEGL14.THE_CONFIG).
    if (configs != nullptr && size > 0)
    {
      configs[0] = reinterpret_cast<EGLConfig>(TOKEN_TAG | (2ULL << 32) | 1);
    }
    if (count != nullptr) *count = 1;
    return EGL_TRUE;
  }

  EGLSurface rive_host_eglCreatePbufferSurface(EGLDisplay dpy,
                                               EGLConfig config,
                                               const EGLint* attributes)
  {
    if (!isToken(dpy))
    {
      return glad_eglCreatePbufferSurface != nullptr
                 ? glad_eglCreatePbufferSurface(dpy, config, attributes)
                 : EGL_NO_SURFACE;
    }
    // A token of the host's own, sized from the attributes; made real when made current.
    static uint64_t next = 1;
    std::lock_guard<std::mutex> lock(g_mutex);
    auto* token = reinterpret_cast<EGLSurface>(TOKEN_TAG | (KIND_SURFACE << 32) |
                                               (0x8000000ULL + next++));
    Surface& surface = g_surfaces[token];
    for (const EGLint* a = attributes; a != nullptr && *a != EGL_NONE; a += 2)
    {
      if (a[0] == EGL_WIDTH) surface.width = a[1];
      if (a[0] == EGL_HEIGHT) surface.height = a[1];
    }
    return token;
  }

  EGLBoolean rive_host_eglDestroySurface(EGLDisplay dpy, EGLSurface surface)
  {
    if (!isToken(dpy) || !isToken(surface) || kind(surface) != KIND_SURFACE)
    {
      return glad_eglDestroySurface != nullptr ? glad_eglDestroySurface(dpy, surface)
                                               : EGL_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    auto found = g_surfaces.find(surface);
    if (found != g_surfaces.end())
    {
      if (found->second.real != EGL_NO_SURFACE)
      {
        glad_eglDestroySurface(g_display, found->second.real);
      }
      g_surfaces.erase(found);
    }
    g_windowSizes.erase(surface);
    return EGL_TRUE;
  }

  EGLBoolean rive_host_eglQueryContext(EGLDisplay dpy,
                                       EGLContext ctx,
                                       EGLint attribute,
                                       EGLint* value)
  {
    if (!isToken(dpy))
    {
      return glad_eglQueryContext != nullptr
                 ? glad_eglQueryContext(dpy, ctx, attribute, value)
                 : EGL_FALSE;
    }
    if (attribute == EGL_CONTEXT_CLIENT_VERSION)
    {
      *value = 3;
      return EGL_TRUE;
    }
    std::lock_guard<std::mutex> lock(g_mutex);
    EGLContext real = realContext(ctx);
    return glad_eglQueryContext(g_display, real, attribute, value);
  }

  EGLint rive_host_eglGetError()
  {
    return glad_eglGetError != nullptr ? glad_eglGetError() : EGL_SUCCESS;
  }
}
