/*
 * TextureHelper.cpp for the host. Filament's is all inside #ifdef __ANDROID__: it reads a Bitmap's
 * pixels through the NDK's AndroidBitmap_lockPixels, which the host has no library for. Here the
 * pixels are copied out with Bitmap.copyPixelsToBuffer instead, into memory the texture upload
 * owns and frees. The Bitmap.Config mapping is Filament's own (TextureHelper.cpp, AutoBitmap).
 *
 * setExternalImage(HardwareBuffer) needs an EGLImage from an AHardwareBuffer, which a host has no
 * way to make: it reports false, as a device without the extension does.
 */

#include <jni.h>

#include <filament/Engine.h>
#include <filament/Texture.h>

#include "common/CallbackUtils.h"
#include <common/JniUtils.h>

#include <cstdlib>

using namespace filament;
using namespace filament::backend;

namespace {

constexpr int BITMAP_CONFIG_ALPHA_8 = 0;
constexpr int BITMAP_CONFIG_RGB_565 = 1;
constexpr int BITMAP_CONFIG_RGBA_F16 = 4;

PixelDataFormat formatOf(int format) {
    switch (format) {
        case BITMAP_CONFIG_ALPHA_8: return PixelDataFormat::ALPHA;
        case BITMAP_CONFIG_RGB_565: return PixelDataFormat::RGB;
        default:                    return PixelDataFormat::RGBA;
    }
}

PixelDataType typeOf(int format) {
    switch (format) {
        case BITMAP_CONFIG_RGB_565:  return PixelDataType::USHORT_565;
        case BITMAP_CONFIG_RGBA_F16: return PixelDataType::HALF;
        default:                     return PixelDataType::UBYTE;
    }
}

/** The bitmap's pixels, copied into malloc'd memory; size in *size. Null on failure. */
void* copyPixels(JNIEnv* env, jobject bitmap, size_t* size) {
    jclass bitmapClass = env->GetObjectClass(bitmap);
    jmethodID getRowBytes = env->GetMethodID(bitmapClass, "getRowBytes", "()I");
    jmethodID getHeight = env->GetMethodID(bitmapClass, "getHeight", "()I");
    jmethodID copyPixelsToBuffer = env->GetMethodID(bitmapClass, "copyPixelsToBuffer",
            "(Ljava/nio/Buffer;)V");
    *size = (size_t) env->CallIntMethod(bitmap, getRowBytes) *
            (size_t) env->CallIntMethod(bitmap, getHeight);
    void* pixels = env->ExceptionCheck() || *size == 0 ? nullptr : malloc(*size);
    if (pixels) {
        jobject buffer = env->NewDirectByteBuffer(pixels, (jlong) *size);
        env->CallVoidMethod(bitmap, copyPixelsToBuffer, buffer);
        env->DeleteLocalRef(buffer);
        if (env->ExceptionCheck()) {
            free(pixels);
            pixels = nullptr;
        }
    }
    env->DeleteLocalRef(bitmapClass);
    return pixels;
}

void freePixels(void* pixels, size_t, void*) {
    free(pixels);
}

void freePixelsAndPost(void* pixels, size_t, void* user) {
    free(pixels);
    JniCallback::postToJavaAndDestroy(static_cast<JniCallback*>(user));
}

void setImage(JNIEnv* env, jlong nativeTexture, jlong nativeEngine, jint level, jint xoffset,
        jint yoffset, jint width, jint height, jobject bitmap, jint format,
        JniCallback* callback) {
    auto* texture = (Texture*) nativeTexture;
    auto* engine = (Engine*) nativeEngine;
    size_t size;
    void* pixels = copyPixels(env, bitmap, &size);
    if (!pixels) {
        if (callback) JniCallback::destroy(callback);
        return;
    }
    Texture::PixelBufferDescriptor desc = callback
            ? Texture::PixelBufferDescriptor(pixels, size, formatOf(format), typeOf(format),
                    callback->getHandler(), &freePixelsAndPost, callback)
            : Texture::PixelBufferDescriptor(pixels, size, formatOf(format), typeOf(format),
                    &freePixels, nullptr);
    filament::android::wrapJni(env, [&]() {
        texture->setImage(*engine, (size_t) level, (uint32_t) xoffset, (uint32_t) yoffset,
                (uint32_t) width, (uint32_t) height, std::move(desc));
    });
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_google_android_filament_android_TextureHelper_nSetBitmap(JNIEnv* env, jclass,
        jlong nativeTexture, jlong nativeEngine, jint level, jint xoffset, jint yoffset,
        jint width, jint height, jobject bitmap, jint format) {
    setImage(env, nativeTexture, nativeEngine, level, xoffset, yoffset, width, height, bitmap,
            format, nullptr);
}

JNIEXPORT void JNICALL
Java_com_google_android_filament_android_TextureHelper_nSetBitmapWithCallback(JNIEnv* env,
        jclass, jlong nativeTexture, jlong nativeEngine, jint level, jint xoffset, jint yoffset,
        jint width, jint height, jobject bitmap, jint format, jobject handler,
        jobject runnable) {
    setImage(env, nativeTexture, nativeEngine, level, xoffset, yoffset, width, height, bitmap,
            format, JniCallback::make(env, handler, runnable));
}

JNIEXPORT jboolean JNICALL
Java_com_google_android_filament_android_TextureHelper_nSetExternalImageByAHB(JNIEnv*, jclass,
        jlong, jlong, jobject) {
    return JNI_FALSE;
}

}  // extern "C"
