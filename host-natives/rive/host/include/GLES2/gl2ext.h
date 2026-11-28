// Host stand-in for <GLES2/gl2ext.h>: glad's OpenGL ES with Rive's additions, as Rive's renderer uses off
// Android (see EGL/egl.h).
#pragma once

#include "glad_custom.h"

// KHR_robustness's reset status, which glad's ES 3 profile leaves out.
#ifndef GL_CONTEXT_LOST_KHR
#define GL_CONTEXT_LOST_KHR 0x0507
#endif
