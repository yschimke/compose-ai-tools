// Host stand-in for <EGL/egl.h>: glad's EGL, as Rive's renderer uses off Android. Every EGL entry
// point is then a glad function pointer, unloaded until a GPU context starts, which the host build
// never does (build.sh), so librive-android links against no EGL library.
#pragma once

#include <glad/egl.h>
