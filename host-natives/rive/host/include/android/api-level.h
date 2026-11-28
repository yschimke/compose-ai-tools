// Host stand-in for the NDK's <android/api-level.h>. android_get_device_api_level() reads the
// simulated device's android.os.Build.VERSION.SDK_INT (host/HostAndroid.cpp).
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#define __ANDROID_API_FUTURE__ 10000

int android_get_device_api_level(void);

#ifdef __cplusplus
}
#endif
