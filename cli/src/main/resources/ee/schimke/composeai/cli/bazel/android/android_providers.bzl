"""Public rules_android providers; internal deployments may substitute this shim.

Only these symbols tie android.bzl to rules_android: the resources provider an
android_library returns, the provider it advertises (which selects the targets
compose_preview's module aspect applies to), and the Android SDK toolchain type
that supplies aapt2 and android.jar.
"""

load("@rules_android//providers:providers.bzl", _AndroidIdeInfo = "AndroidIdeInfo", _StarlarkAndroidResourcesInfo = "StarlarkAndroidResourcesInfo")

# Advertised (rule `provides`) by android_library since rules_android 0.6.6.
AndroidIdeInfo = _AndroidIdeInfo

StarlarkAndroidResourcesInfo = _StarlarkAndroidResourcesInfo

ANDROID_SDK_TOOLCHAIN_TYPE = Label("@rules_android//toolchains/android_sdk:toolchain_type")
