package com.example.samplecmp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.preview.AnimatedPreview
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * SkSL feature-survey gallery — a spread of well-known shader techniques (raymarched SDF, fBm
 * noise, Julia escape-time, content-sampling render effect), each chosen to exercise a *different*
 * SkSL capability so we can see which the desktop/skiko capture path handles. The Android (AGSL)
 * twins live in `:samples:android` `ShaderGalleryPreviews.kt`; comparing the two PNGs is the "what
 * works / what differs across backends" matrix.
 *
 * All static: `iTime` is pinned to a fixed phase so each capture is deterministic (no
 * `@AnimatedPreview`). Captures are 240×240dp ShaderBrush fills unless noted.
 *
 * Technique credits — these are textbook GPU techniques adapted from Inigo Quilez's writing/shaders
 * (released under the MIT License). The SkSL here is rewritten for Compose, but the maths is his:
 * - Raymarching loop, SDF primitives & tetrahedron-normal:
 *   https://iquilezles.org/articles/raymarchingdf/ and
 *   https://iquilezles.org/articles/distfunctions/
 * - Value noise + fBm and the `sin(dot(p, vec2(127.1, 311.7))) * 43758.5453` hash:
 *   https://www.shadertoy.com/view/lsf3WH and https://iquilezles.org/articles/fbm/
 * - Cosine palette (`0.5 + 0.5*cos(...)`): https://iquilezles.org/articles/palettes/ The Julia
 *   escape-time iteration is classic public-domain complex-dynamics maths; only its colouring uses
 *   the palette above.
 *
 * Boundary note — every loop here uses a **literal constant bound**. Skia's shading language (both
 * SkSL on this backend and AGSL on Android) rejects a uniform/dynamic trip count with `error: loop
 * index must be compared with a constant expression`; the pipeline surfaces that as a clean
 * `.error.json` sidecar rather than a crash, but the shader won't render. Keep loop bounds literal.
 */
private const val FIXED_TIME = 1.2f

private fun shaderBrush(sksl: String, widthPx: Float, heightPx: Float, time: Float): Brush {
  val effect = RuntimeEffect.makeForShader(sksl)
  val builder = RuntimeShaderBuilder(effect)
  builder.uniform("iResolution", widthPx, heightPx)
  builder.uniform("iTime", time)
  return ShaderBrush(builder.makeShader())
}

@Composable
private fun ShaderCard(sksl: String) {
  val sizeDp = 240.dp
  val px = with(LocalDensity.current) { sizeDp.toPx() }
  Box(
    modifier =
      Modifier.size(sizeDp).background(remember(sksl, px) { shaderBrush(sksl, px, px, FIXED_TIME) })
  )
}

/**
 * Animated twin of [ShaderCard]: a `rememberInfiniteTransition` ramps `iTime` from `0` to `2π`
 * every 2s, captured as a GIF by the desktop `@AnimatedPreview` path. Reading `time` in composition
 * rebuilds the shader with the new phase each frame. Only used with programs that loop seamlessly
 * over a `2π` `iTime` (the raymarch light + wobble, the Julia `c`-orbit, the fBm domain orbit).
 */
@Composable
private fun AnimatedShaderCard(sksl: String) {
  val sizeDp = 240.dp
  val px = with(LocalDensity.current) { sizeDp.toPx() }
  val transition = rememberInfiniteTransition(label = "shader-time")
  val time by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = (2.0 * Math.PI).toFloat(),
      animationSpec =
        infiniteRepeatable(tween(durationMillis = 2000, easing = LinearEasing), RepeatMode.Restart),
      label = "iTime",
    )
  val effect = remember(sksl) { RuntimeEffect.makeForShader(sksl) }
  val builder = remember(effect) { RuntimeShaderBuilder(effect) }
  builder.uniform("iResolution", px, px)
  builder.uniform("iTime", time)
  Box(modifier = Modifier.size(sizeDp).background(ShaderBrush(builder.makeShader())))
}

// ---------------------------------------------------------------------------------------------
// 1. Raymarched SDF sphere with a single light — bounded for-loop, 3D vector math, normals.
// ---------------------------------------------------------------------------------------------
private val RAYMARCH_SKSL =
  """
  uniform float2 iResolution;
  uniform float iTime;

  float sdSphere(float3 p, float r) { return length(p) - r; }

  float map(float3 p) {
    float wobble = 0.12 * sin(4.0 * p.x + iTime) * sin(4.0 * p.y + iTime);
    return sdSphere(p, 1.0) + wobble;
  }

  float3 calcNormal(float3 p) {
    float2 e = float2(0.001, 0.0);
    return normalize(float3(
      map(p + e.xyy) - map(p - e.xyy),
      map(p + e.yxy) - map(p - e.yxy),
      map(p + e.yyx) - map(p - e.yyx)));
  }

  half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * iResolution) / iResolution.y;
    float3 ro = float3(0.0, 0.0, -3.0);
    float3 rd = normalize(float3(uv, 1.6));
    float t = 0.0;
    float3 col = float3(0.04, 0.05, 0.09);
    for (int i = 0; i < 80; i++) {
      float3 p = ro + rd * t;
      float d = map(p);
      if (d < 0.001) {
        float3 n = calcNormal(p);
        float3 ld = normalize(float3(sin(iTime), 0.7, -0.6));
        float diff = max(dot(n, ld), 0.0);
        float spec = pow(max(dot(reflect(-ld, n), -rd), 0.0), 32.0);
        col = float3(0.25, 0.55, 1.0) * diff + float3(1.0) * spec + 0.06;
        break;
      }
      if (t > 8.0) break;
      t += d;
    }
    return half4(half3(col), 1.0);
  }
  """
    .trimIndent()

@Preview(name = "Shader Gallery — Raymarch SDF")
@Composable
fun ShaderRaymarchPreview() = ShaderCard(RAYMARCH_SKSL)

// ---------------------------------------------------------------------------------------------
// 2. fBm value-noise clouds — hash, fract/floor, fractal octave loop.
// ---------------------------------------------------------------------------------------------
private val FBM_SKSL =
  """
  uniform float2 iResolution;
  uniform float iTime;

  float hash(float2 p) { return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453); }

  float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i + float2(0.0, 0.0)), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
  }

  float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 6; i++) {
      v += a * noise(p);
      p = p * 2.0;
      a = a * 0.5;
    }
    return v;
  }

  half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    // Periodic domain orbit (not a linear drift) so an iTime sweep of 0..2π loops seamlessly.
    float2 p = uv * 4.0 + 0.6 * float2(cos(iTime), sin(iTime));
    float v = fbm(p + fbm(p));
    float3 sky = float3(0.15, 0.25, 0.45);
    float3 cloud = float3(1.0, 0.98, 0.95);
    return half4(half3(mix(sky, cloud, v)), 1.0);
  }
  """
    .trimIndent()

@Preview(name = "Shader Gallery — fBm Clouds")
@Composable
fun ShaderFbmPreview() = ShaderCard(FBM_SKSL)

// ---------------------------------------------------------------------------------------------
// 3. Julia set — escape-time loop with a data-dependent break + palette.
// ---------------------------------------------------------------------------------------------
private val JULIA_SKSL =
  """
  uniform float2 iResolution;
  uniform float iTime;

  float3 palette(float t) {
    return 0.5 + 0.5 * cos(6.2831 * (float3(1.0, 1.0, 1.0) * t + float3(0.0, 0.33, 0.67)));
  }

  half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * iResolution) / iResolution.y * 1.6;
    float2 c = float2(0.355, 0.355) + 0.12 * float2(cos(iTime), sin(iTime));
    float2 z = uv;
    const int MAX = 96;
    int iter = 0;
    for (int i = 0; i < MAX; i++) {
      z = float2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
      if (dot(z, z) > 4.0) break;
      iter++;
    }
    float m = float(iter) / float(MAX);
    if (iter >= MAX) return half4(0.0, 0.0, 0.0, 1.0);
    return half4(half3(palette(m + 0.4)), 1.0);
  }
  """
    .trimIndent()

@Preview(name = "Shader Gallery — Julia Set")
@Composable
fun ShaderJuliaPreview() = ShaderCard(JULIA_SKSL)

// ---------------------------------------------------------------------------------------------
// Animated companions — the three procedural fills above, looped as GIFs via @AnimatedPreview.
// Each program is already periodic in `iTime` (raymarch light + wobble, Julia c-orbit, fBm domain
// orbit), so a 0..2π ramp produces a seamless 2s loop.
// ---------------------------------------------------------------------------------------------
@Preview(name = "Shader Gallery — Raymarch SDF (animated)")
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 50, showCurves = false)
@Composable
fun ShaderRaymarchAnimatedPreview() = AnimatedShaderCard(RAYMARCH_SKSL)

@Preview(name = "Shader Gallery — fBm Clouds (animated)")
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 50, showCurves = false)
@Composable
fun ShaderFbmAnimatedPreview() = AnimatedShaderCard(FBM_SKSL)

@Preview(name = "Shader Gallery — Julia Set (animated)")
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 50, showCurves = false)
@Composable
fun ShaderJuliaAnimatedPreview() = AnimatedShaderCard(JULIA_SKSL)

// ---------------------------------------------------------------------------------------------
// 4. Content-sampling RenderEffect — `uniform shader content` distorts the real composable beneath
//    it via Modifier.graphicsLayer(renderEffect = ...). Tests the child-shader / renderEffect path
//    rather than a ShaderBrush fill.
// ---------------------------------------------------------------------------------------------
private val DISTORT_SKSL =
  """
  uniform float2 iResolution;
  uniform float iTime;
  uniform shader content;

  half4 main(float2 fragCoord) {
    float2 uv = fragCoord;
    uv.x += sin(fragCoord.y * 0.06 + iTime) * 12.0;
    uv.y += cos(fragCoord.x * 0.06 + iTime) * 12.0;
    return content.eval(uv);
  }
  """
    .trimIndent()

@Preview(name = "Shader Gallery — RenderEffect Distort")
@Composable
fun ShaderRenderEffectPreview() {
  val sizeDp = 240.dp
  val px = with(LocalDensity.current) { sizeDp.toPx() }
  val renderEffect =
    remember(px) {
      val effect = RuntimeEffect.makeForShader(DISTORT_SKSL)
      val builder = RuntimeShaderBuilder(effect)
      builder.uniform("iResolution", px, px)
      builder.uniform("iTime", FIXED_TIME)
      ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
    }
  Box(
    modifier =
      Modifier.size(sizeDp).graphicsLayer(renderEffect = renderEffect).background(Color(0xFF101820))
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
      Text(
        "RenderEffect",
        color = Color(0xFF7DE2FF),
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
      )
      Text("samples the", color = Color.White, fontSize = 20.sp)
      Text("composable beneath", color = Color(0xFFFFB86C), fontSize = 20.sp)
      Text("and warps it", color = Color.White, fontSize = 20.sp)
      Box(
        modifier =
          Modifier.padding(top = 12.dp).size(60.dp).background(MaterialTheme.colorScheme.primary)
      )
    }
  }
}
