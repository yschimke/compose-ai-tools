#pragma once

// Stands in for the NDK header. The simulator runs graphics-path on API 34 and newer, where the
// library only registers its natives and converts conics: `PathIteratorPreApi34Impl`, the one
// caller of the Skia-layout code in pathway.cpp, is never constructed there.
static inline int android_get_device_api_level() { return 34; }
