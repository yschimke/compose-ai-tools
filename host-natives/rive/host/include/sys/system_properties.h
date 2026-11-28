// Host stand-in for bionic's <sys/system_properties.h>. Only ro.build.version.sdk is answered,
// from the simulated device's android.os.Build.VERSION.SDK_INT (host/HostAndroid.cpp).
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#define PROP_VALUE_MAX 92

int __system_property_get(const char* name, char* value);

#ifdef __cplusplus
}
#endif
