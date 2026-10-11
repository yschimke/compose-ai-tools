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
 * Android `RuntimeShader` (AGSL) smoke test: the same gradient-blob program as the CMP sample
 * ([com.example.samplecmp.RuntimeShaderGradientBlobPreview]), but rendered through Robolectric's
 * NATIVE graphics (libhwui via `RuntimeShaderNatives`) rather than skiko. API 33+, satisfied by the
 * module's `sdk=35`; the `SDK_INT` guard falls back to a flat fill on older devices.
 */
// AGSL source — a near-subset of SkSL.
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
 * A 220×220dp box with the AGSL brush: the same ringed gradient as the CMP preview means the native
 * AGSL path works; a flat box or error sidecar means it doesn't.
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
 * Animated variant: `iTime` phase-shifts the rings outward; `38·d − iTime` over 2π loops
 * seamlessly.
 */
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
 * The animated shader as a GIF: `iTime` ramps every 2s via `rememberInfiniteTransition` and is set
 * in `drawWithCache.onDrawBehind`, so each paused-clock step redraws with the new phase.
 * `durationMs = 2000` captures exactly one loop.
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
