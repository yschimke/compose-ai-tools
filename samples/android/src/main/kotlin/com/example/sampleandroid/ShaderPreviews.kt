package com.example.sampleandroid

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview

/**
 * Android `RuntimeShader` (AGSL) smoke test — the Android-backend half of "shader support".
 *
 * AGSL is nearly a subset of SkSL, so this is the *same* gradient-blob program as the CMP sample
 * ([com.example.samplecmp.RuntimeShaderGradientBlobPreview]), but the capture path is different:
 * `android.graphics.RuntimeShader` is an Android-framework class backed by libhwui, and under the
 * preview pipeline it renders through **Robolectric's NATIVE graphics mode** (the `nativeruntime`
 * jar's `RuntimeShaderNatives` JNI bindings) rather than skiko. This `@Preview` is the end-to-end
 * probe for whether that native AGSL compile + raster path captures cleanly.
 *
 * `RuntimeShader` is API 33+. The sample pins Robolectric to `sdk=35` (see the module build), so the
 * render sandbox satisfies the requirement; the `Build.VERSION.SDK_INT` guard keeps the composable
 * harmless on older on-device runs (it falls back to a flat fill).
 */
// AGSL source (Android Graphics Shading Language) — a near-subset of SkSL.
private const val GRADIENT_BLOB_AGSL =
  """
  uniform float2 iResolution;

  half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float d = distance(uv, float2(0.5, 0.5));

    half3 base = mix(half3(0.10, 0.30, 0.95), half3(0.98, 0.42, 0.20), half(uv.x));

    float rings = 0.65 + 0.35 * sin(38.0 * d);
    float vignette = smoothstep(0.75, 0.05, d);

    half3 col = base * half(rings) * half(vignette);
    return half4(col, 1.0);
  }
  """

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun runtimeShaderBrush(widthPx: Float, heightPx: Float): ShaderBrush {
  val shader = remember { RuntimeShader(GRADIENT_BLOB_AGSL) }
  shader.setFloatUniform("iResolution", widthPx, heightPx)
  return remember(shader) { ShaderBrush(shader) }
}

/**
 * A 220×220dp box filled with the AGSL runtime-shader brush. If this captures as the same ringed
 * radial gradient the CMP preview produces, the Android/Robolectric NATIVE AGSL path works end to
 * end; a flat box or a render error sidecar means it doesn't (yet) and the skiko-bridge fallback is
 * warranted.
 */
@Preview(name = "Runtime Shader — Gradient Blob (AGSL)")
@Composable
fun RuntimeShaderGradientBlobPreview() {
  val sizeDp = 220.dp
  val px = with(LocalDensity.current) { sizeDp.toPx() }
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Box(modifier = Modifier.size(sizeDp).background(runtimeShaderBrush(px, px)))
  } else {
    Box(modifier = Modifier.size(sizeDp).background(Color.DarkGray))
  }
}

/**
 * Animated variant of [GRADIENT_BLOB_AGSL] — an `iTime` uniform phase-shifts the rings so they
 * travel outward. `38·d − iTime` over a `2π` ramp makes one seamless loop (the `sin` period).
 */
// AGSL — same blob, with a time-driven phase on the ring term.
private const val GRADIENT_BLOB_ANIMATED_AGSL =
  """
  uniform float2 iResolution;
  uniform float iTime;

  half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float d = distance(uv, float2(0.5, 0.5));

    half3 base = mix(half3(0.10, 0.30, 0.95), half3(0.98, 0.42, 0.20), half(uv.x));

    float rings = 0.65 + 0.35 * sin(38.0 * d - iTime);
    float vignette = smoothstep(0.75, 0.05, d);

    half3 col = base * half(rings) * half(vignette);
    return half4(col, 1.0);
  }
  """

/**
 * The animated AGSL shader as a GIF.
 *
 * Animation is driven the ordinary Compose way — a `rememberInfiniteTransition` ramps `iTime` from
 * `0` to `2π` and back to `0` every 2s — so the `@AnimatedPreview` paused-clock path picks it up
 * just like any other `InfiniteTransition`: the renderer advances `mainClock` by `frameIntervalMs`,
 * re-reading the uniform each step, and encodes the frames as `renders/<id>.gif`. `iTime` is set
 * inside `drawWithCache.onDrawBehind` so each clock advance re-runs the draw with the new phase
 * (the resolution uniform + brush stay cached until the size changes).
 *
 * `durationMs = 2000` matches the ramp's period — an `InfiniteTransition` has no inherent duration,
 * so the GIF window is set explicitly to capture exactly one seamless loop. `showCurves = false`
 * keeps the GIF to just the shader (the time ramp would otherwise add a curve strip).
 */
@Preview(name = "Runtime Shader — Animated Blob (AGSL)")
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 50, showCurves = false)
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun RuntimeShaderAnimatedBlobPreview() {
  val sizeDp = 220.dp
  val transition = rememberInfiniteTransition(label = "shader-time")
  val time by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = (2.0 * Math.PI).toFloat(),
      animationSpec =
        infiniteRepeatable(tween(durationMillis = 2000, easing = LinearEasing), RepeatMode.Restart),
      label = "iTime",
    )
  val shader = remember { RuntimeShader(GRADIENT_BLOB_ANIMATED_AGSL) }
  Box(
    modifier =
      Modifier.size(sizeDp).drawWithCache {
        shader.setFloatUniform("iResolution", size.width, size.height)
        val brush = ShaderBrush(shader)
        onDrawBehind {
          shader.setFloatUniform("iTime", time)
          drawRect(brush)
        }
      }
  )
}
