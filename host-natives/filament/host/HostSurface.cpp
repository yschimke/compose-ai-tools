/*
 * The host side of filament-jni's window and frame handling, in place of nativewindow/Android.cpp.
 *
 * On a device, Engine.createSwapChain(surface) wraps the Surface's ANativeWindow in an EGL window
 * surface, and eglSwapBuffers hands each frame to the compositor. Under Robolectric there is no
 * EGL window to wrap, so here:
 *
 *  - createSwapChain(surface) makes a headless (pbuffer) swap chain at the surface's size, made
 *    readable, and remembers the surface;
 *  - each frame rendered into it is read back (Renderer::readPixels, issued just before endFrame)
 *    and posted to the surface through Surface.lockHardwareCanvas(), the public Java API the
 *    simulator's SurfaceView already shows.
 *
 * Filament.cpp, Engine.cpp and Renderer.cpp are compiled unchanged: build.sh renames the JNI entry
 * points defined below (-D<name>=filament_upstream_<name>), and these call the originals.
 *
 * An sRGB swap chain (CONFIG_SRGB_COLORSPACE, which apps request at feature level 0, where nothing
 * else encodes to sRGB) gets a linear pbuffer instead: Mesa refuses EGL_GL_COLORSPACE_KHR on a
 * pbuffer, which leaves the swap chain without a surface and takes the app down. The encoding the
 * surface would have done is applied to each frame as it is read back.
 *
 * Filament's native log (utils::slog) goes to android.util.Log under the tag "Filament", as it goes
 * to logcat on a device, through the hook utils-log.patch adds (filament_host_log). Off a device it
 * would print to stdout, which the simulator's control channel owns.
 *
 * JNI_OnLoad also installs Filament's AndroidPlatform. Platform.get() picks it from java.vendor,
 * which under Robolectric is the host JDK's, so it would otherwise settle on UnknownPlatform and
 * reject every Surface ("Invalid surface").
 */

#include <jni.h>

#include <filament/Engine.h>
#include <filament/Renderer.h>
#include <filament/SwapChain.h>
#include <backend/PixelBufferDescriptor.h>

#include "private/backend/VirtualMachineEnv.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>

using namespace filament;

extern "C" {
jint filament_upstream_JNI_OnLoad(JavaVM* vm, void* reserved);
jlong filament_upstream_nCreateSwapChain(JNIEnv*, jclass, jlong, jobject, jlong);
jboolean filament_upstream_nDestroySwapChain(JNIEnv*, jclass, jlong, jlong);
jboolean filament_upstream_nBeginFrame(JNIEnv*, jclass, jlong, jlong, jlong);
void filament_upstream_nEndFrame(JNIEnv*, jclass, jlong);
}

namespace {

/** A Surface a headless swap chain posts its frames to, and the bitmap it copies them through. */
struct HostSurface {
    jobject surface = nullptr;  // global ref: android.view.Surface
    jobject bitmap = nullptr;   // global ref: android.graphics.Bitmap, width x height ARGB_8888
    uint32_t width = 0;
    uint32_t height = 0;
    bool srgb = false;  // encode each frame to sRGB as it is read back (see the top of the file)
};

/** Linear to sRGB, per 8-bit channel value: the transfer function an sRGB surface applies. */
const std::array<uint8_t, 256>& srgbEncode() {
    static const std::array<uint8_t, 256> table = [] {
        std::array<uint8_t, 256> t{};
        for (int i = 0; i < 256; i++) {
            double const c = i / 255.0;
            double const s = c <= 0.0031308 ? c * 12.92 : 1.055 * std::pow(c, 1.0 / 2.4) - 0.055;
            t[i] = (uint8_t) std::lround(std::fmin(std::fmax(s, 0.0), 1.0) * 255.0);
        }
        return t;
    }();
    return table;
}

struct Java {
    jclass surface;
    jmethodID lockHardwareCanvas;
    jmethodID unlockCanvasAndPost;
    jmethodID isValid;
    jclass canvas;
    jmethodID getWidth;
    jmethodID getHeight;
    jmethodID save;
    jmethodID restore;
    jmethodID scale;
    jmethodID drawBitmap;
    jclass bitmap;
    jmethodID createBitmap;
    jmethodID copyPixelsFromBuffer;
    jmethodID recycle;
    jobject argb8888;  // global ref: Bitmap.Config.ARGB_8888
    jclass log;
    jmethodID println;
};

Java gJava{};
std::mutex gLock;
// Swap chain -> the surface it posts to. shared_ptr: a frame read back after its swap chain is
// destroyed still holds its surface until it has been posted (or dropped).
std::unordered_map<SwapChain*, std::shared_ptr<HostSurface>> gSurfaces;
// Renderer -> the swap chain of the frame it is in (between beginFrame and endFrame).
std::unordered_map<Renderer*, SwapChain*> gFrames;

bool cleared(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }
    return true;
}

jclass globalClass(JNIEnv* env, const char* name) {
    jclass local = env->FindClass(name);
    auto global = (jclass) env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    return global;
}

void loadJava(JNIEnv* env) {
    gJava.surface = globalClass(env, "android/view/Surface");
    gJava.lockHardwareCanvas = env->GetMethodID(gJava.surface, "lockHardwareCanvas",
            "()Landroid/graphics/Canvas;");
    gJava.unlockCanvasAndPost = env->GetMethodID(gJava.surface, "unlockCanvasAndPost",
            "(Landroid/graphics/Canvas;)V");
    gJava.isValid = env->GetMethodID(gJava.surface, "isValid", "()Z");
    gJava.canvas = globalClass(env, "android/graphics/Canvas");
    gJava.getWidth = env->GetMethodID(gJava.canvas, "getWidth", "()I");
    gJava.getHeight = env->GetMethodID(gJava.canvas, "getHeight", "()I");
    gJava.save = env->GetMethodID(gJava.canvas, "save", "()I");
    gJava.restore = env->GetMethodID(gJava.canvas, "restore", "()V");
    gJava.scale = env->GetMethodID(gJava.canvas, "scale", "(FFFF)V");
    gJava.drawBitmap = env->GetMethodID(gJava.canvas, "drawBitmap",
            "(Landroid/graphics/Bitmap;FFLandroid/graphics/Paint;)V");
    gJava.bitmap = globalClass(env, "android/graphics/Bitmap");
    gJava.createBitmap = env->GetStaticMethodID(gJava.bitmap, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    gJava.copyPixelsFromBuffer = env->GetMethodID(gJava.bitmap, "copyPixelsFromBuffer",
            "(Ljava/nio/Buffer;)V");
    gJava.recycle = env->GetMethodID(gJava.bitmap, "recycle", "()V");
    gJava.log = globalClass(env, "android/util/Log");
    gJava.println = env->GetStaticMethodID(gJava.log, "println",
            "(ILjava/lang/String;Ljava/lang/String;)I");
    jclass config = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb = env->GetStaticFieldID(config, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject value = env->GetStaticObjectField(config, argb);
    gJava.argb8888 = env->NewGlobalRef(value);
    env->DeleteLocalRef(value);
    env->DeleteLocalRef(config);
    cleared(env);
}

/**
 * The surface's size, from the canvas a lock hands out. Posts that (untouched) canvas, so the
 * surface starts with an empty frame, as a device's does until the first swap.
 */
bool surfaceSize(JNIEnv* env, jobject surface, uint32_t* width, uint32_t* height) {
    jobject canvas = env->CallObjectMethod(surface, gJava.lockHardwareCanvas);
    if (!cleared(env) || !canvas) return false;
    jint w = env->CallIntMethod(canvas, gJava.getWidth);
    jint h = env->CallIntMethod(canvas, gJava.getHeight);
    env->CallVoidMethod(surface, gJava.unlockCanvasAndPost, canvas);
    env->DeleteLocalRef(canvas);
    if (!cleared(env) || w <= 0 || h <= 0) return false;
    *width = (uint32_t) w;
    *height = (uint32_t) h;
    return true;
}

void release(JNIEnv* env, HostSurface* host) {
    if (host->bitmap) {
        env->CallVoidMethod(host->bitmap, gJava.recycle);
        cleared(env);
        env->DeleteGlobalRef(host->bitmap);
    }
    if (host->surface) env->DeleteGlobalRef(host->surface);
    delete host;
}

std::shared_ptr<HostSurface> makeHostSurface(JNIEnv* env, jobject surface, uint32_t w, uint32_t h) {
    jobject bitmap = env->CallStaticObjectMethod(gJava.bitmap, gJava.createBitmap,
            (jint) w, (jint) h, gJava.argb8888);
    if (!cleared(env) || !bitmap) return nullptr;
    auto* host = new HostSurface{env->NewGlobalRef(surface), env->NewGlobalRef(bitmap), w, h};
    env->DeleteLocalRef(bitmap);
    return std::shared_ptr<HostSurface>(host, [](HostSurface* s) {
        release(VirtualMachineEnv::get().getEnvironment(), s);
    });
}

/** A frame read back from a swap chain, on its way to that swap chain's surface. */
struct Frame {
    std::shared_ptr<HostSurface> host;
    void* pixels;
};

/**
 * Copies a read-back frame (RGBA, rows bottom-up as GL reads them) into the surface's bitmap and
 * draws it, flipped upright, onto the surface. Runs on the thread that drives the engine, where
 * Filament delivers callbacks that have no handler.
 */
void post(void* pixels, size_t size, void* user) {
    auto* frame = static_cast<Frame*>(user);
    HostSurface& host = *frame->host;
    if (host.srgb) {
        auto const& encode = srgbEncode();
        auto* p = static_cast<uint8_t*>(pixels);
        for (size_t i = 0; i + 3 < size; i += 4) {  // RGBA: alpha stays linear
            p[i] = encode[p[i]];
            p[i + 1] = encode[p[i + 1]];
            p[i + 2] = encode[p[i + 2]];
        }
    }
    JNIEnv* env = VirtualMachineEnv::get().getEnvironment();
    if (env->CallBooleanMethod(host.surface, gJava.isValid) && cleared(env)) {
        jobject buffer = env->NewDirectByteBuffer(pixels, (jlong) size);
        env->CallVoidMethod(host.bitmap, gJava.copyPixelsFromBuffer, buffer);
        env->DeleteLocalRef(buffer);
        jobject canvas = cleared(env)
                ? env->CallObjectMethod(host.surface, gJava.lockHardwareCanvas) : nullptr;
        if (cleared(env) && canvas) {
            env->CallIntMethod(canvas, gJava.save);
            env->CallVoidMethod(canvas, gJava.scale, 1.0f, -1.0f, 0.0f, host.height / 2.0f);
            env->CallVoidMethod(canvas, gJava.drawBitmap, host.bitmap, 0.0f, 0.0f, nullptr);
            env->CallVoidMethod(canvas, gJava.restore);
            env->CallVoidMethod(host.surface, gJava.unlockCanvasAndPost, canvas);
            env->DeleteLocalRef(canvas);
            cleared(env);
        }
    } else {
        cleared(env);
    }
    free(pixels);
    delete frame;
}

}  // namespace

extern "C" {

/**
 * utils::slog's sink (utils-log.patch): one flushed line at Filament's LogStream priority (debug,
 * error, warning, info, verbose). False until the Java side is loaded, and Filament prints it
 * itself, to stderr or stdout.
 */
bool filament_host_log(int priority, char const* message) {
    if (!gJava.println || !VirtualMachineEnv::hasVirtualMachine()) return false;
    int android;
    switch (priority) {
        case 0: android = 3; break;  // DEBUG
        case 1: android = 6; break;  // ERROR
        case 2: android = 5; break;  // WARN
        case 3: android = 4; break;  // INFO
        default: return true;        // VERBOSE: Filament's release builds drop it too
    }
    std::string text(message);
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.pop_back();
    if (text.empty()) return true;
    // NewStringUTF wants modified UTF-8; Filament's lines are ASCII, so anything else is replaced.
    for (char& c : text) {
        if (static_cast<unsigned char>(c) > 0x7f) c = '?';
    }
    JNIEnv* env = VirtualMachineEnv::get().getEnvironment();
    jstring tag = env->NewStringUTF("Filament");
    jstring msg = env->NewStringUTF(text.c_str());
    env->CallStaticIntMethod(gJava.log, gJava.println, (jint) android, tag, msg);
    env->DeleteLocalRef(tag);
    env->DeleteLocalRef(msg);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    jint version = filament_upstream_JNI_OnLoad(vm, reserved);
    JNIEnv* env;
    if (version < 0 || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return version;
    }
    loadJava(env);
    // Platform.mCurrentPlatform = new AndroidPlatform(): see the top of this file.
    jclass platform = env->FindClass("com/google/android/filament/Platform");
    jclass android = env->FindClass("com/google/android/filament/AndroidPlatform");
    if (cleared(env) && platform && android) {
        jmethodID init = env->GetMethodID(android, "<init>", "()V");
        jobject instance = init ? env->NewObject(android, init) : nullptr;
        jfieldID current = env->GetStaticFieldID(platform, "mCurrentPlatform",
                "Lcom/google/android/filament/Platform;");
        if (cleared(env) && instance && current) {
            env->SetStaticObjectField(platform, current, instance);
        }
        cleared(env);
    }
    return version;
}

// nativewindow/Android.cpp's three functions. The host swap chain never wraps a native window.
void* getNativeWindow(JNIEnv*, jclass, jobject) { return nullptr; }
jlong createNativeSurface(jint, jint) { return 0; }
void destroyNativeSurface(jlong) {}

JNIEXPORT jlong JNICALL
Java_com_google_android_filament_Engine_nCreateSwapChain(JNIEnv* env, jclass klass,
        jlong nativeEngine, jobject surface, jlong flags) {
    auto* engine = (Engine*) nativeEngine;
    uint32_t width, height;
    if (!surface || !env->IsInstanceOf(surface, gJava.surface) ||
            !surfaceSize(env, surface, &width, &height)) {
        // Not a Surface (or not a usable one): what the device build would do with it.
        return filament_upstream_nCreateSwapChain(env, klass, nativeEngine, surface, flags);
    }
    auto host = makeHostSurface(env, surface, width, height);
    if (!host) return 0;
    uint64_t chainFlags = (uint64_t) flags | SwapChain::CONFIG_READABLE;
    if (chainFlags & SwapChain::CONFIG_SRGB_COLORSPACE) {
        host->srgb = true;
        chainFlags &= ~SwapChain::CONFIG_SRGB_COLORSPACE;
    }
    SwapChain* swapChain = engine->createSwapChain(width, height, chainFlags);
    if (!swapChain) return 0;
    std::lock_guard<std::mutex> lock(gLock);
    gSurfaces[swapChain] = std::move(host);
    return (jlong) swapChain;
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_filament_Engine_nDestroySwapChain(JNIEnv* env, jclass klass,
        jlong nativeEngine, jlong nativeSwapChain) {
    {
        std::lock_guard<std::mutex> lock(gLock);
        gSurfaces.erase((SwapChain*) nativeSwapChain);
        for (auto it = gFrames.begin(); it != gFrames.end();) {
            it = it->second == (SwapChain*) nativeSwapChain ? gFrames.erase(it) : std::next(it);
        }
    }
    return filament_upstream_nDestroySwapChain(env, klass, nativeEngine, nativeSwapChain);
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_filament_Renderer_nBeginFrame(JNIEnv* env, jclass klass,
        jlong nativeRenderer, jlong nativeSwapChain, jlong vsyncSteadyClockTimeNano) {
    jboolean begun = filament_upstream_nBeginFrame(env, klass, nativeRenderer, nativeSwapChain,
            vsyncSteadyClockTimeNano);
    std::lock_guard<std::mutex> lock(gLock);
    if (begun) {
        gFrames[(Renderer*) nativeRenderer] = (SwapChain*) nativeSwapChain;
    } else {
        gFrames.erase((Renderer*) nativeRenderer);
    }
    return begun;
}

JNIEXPORT void JNICALL
Java_com_google_android_filament_Renderer_nEndFrame(JNIEnv* env, jclass klass,
        jlong nativeRenderer) {
    auto* renderer = (Renderer*) nativeRenderer;
    std::shared_ptr<HostSurface> host;
    {
        std::lock_guard<std::mutex> lock(gLock);
        auto frame = gFrames.find(renderer);
        if (frame != gFrames.end()) {
            auto surface = gSurfaces.find(frame->second);
            if (surface != gSurfaces.end()) host = surface->second;
            gFrames.erase(frame);
        }
    }
    if (host) {
        size_t size = (size_t) host->width * host->height * 4;
        void* pixels = malloc(size);
        if (pixels) {
            renderer->readPixels(0, 0, host->width, host->height,
                    backend::PixelBufferDescriptor(pixels, size,
                            backend::PixelDataFormat::RGBA, backend::PixelDataType::UBYTE,
                            &post, new Frame{host, pixels}));
        }
    }
    filament_upstream_nEndFrame(env, klass, nativeRenderer);
}

}  // extern "C"
