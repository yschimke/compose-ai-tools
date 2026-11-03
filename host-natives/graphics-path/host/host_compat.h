#pragma once

// Force-included ahead of the AndroidX sources, which rely on bionic and the NDK's libc++ reaching
// `size_t`, `std::sqrtf` and `__unused` through headers that glibc, libstdc++ and Apple's SDK
// leave out.
#include <cmath>
#include <cstddef>

namespace std {
using ::sqrtf;
}

#ifndef __unused
#define __unused __attribute__((unused))
#endif
