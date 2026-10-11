package com.example.samplecmp

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Compose Multiplatform / Desktop runtime-shader smoke test. The desktop renderer is
 * `ImageComposeScene` on a skiko CPU raster surface, where [RuntimeEffect] runs SkSL directly — so
 * a [ShaderBrush] from compiled SkSL captures with no renderer changes. The Android (AGSL)
 * counterpart goes through Robolectric's native runtime and has its own samples. Skiko is
 * compile-visible via `compose.desktop.currentOs`.
 */
private val GRADIENT_BLOB_SKSL =
  """
  uniform float2 iResolution;

  half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution;
    float d = distance(uv, float2(0.5, 0.5));

    // Horizontal hue sweep so the shader is unmistakably procedural (not a flat fill).
    half3 base = mix(half3(0.10, 0.30, 0.95), half3(0.98, 0.42, 0.20), half(uv.x));

    // Concentric rings + radial vignette — both pure functions of fragCoord, so the capture is
    // deterministic without any animation clock.
    float rings = 0.65 + 0.35 * sin(38.0 * d);
    float vignette = smoothstep(0.75, 0.05, d);

    half3 col = base * half(rings) * half(vignette);
    return half4(col, 1.0);
  }
  """
    .trimIndent()

/**
 * A [ShaderBrush] from [GRADIENT_BLOB_SKSL] with the draw size as `iResolution`, so it's centred at
 * any density. The effect and builder are remembered to avoid recompiling.
 */
@Composable
private fun gradientBlobBrush(widthPx: Float, heightPx: Float): Brush {
  val effect = remember { RuntimeEffect.makeForShader(GRADIENT_BLOB_SKSL) }
  val builder = remember(effect) { RuntimeShaderBuilder(effect) }
  builder.uniform("iResolution", widthPx, heightPx)
  return ShaderBrush(builder.makeShader().asComposeShader())
}

/** A 220×220dp box with the shader brush: ringed radial gradient means the SkSL path works. */
@Preview(name = "Runtime Shader — Gradient Blob")
@Composable
fun RuntimeShaderGradientBlobPreview() {
  val sizeDp = 220.dp
  val px = with(LocalDensity.current) { sizeDp.toPx() }
  Box(
    modifier =
      Modifier.size(sizeDp)
        .clip(androidx.compose.ui.graphics.RectangleShape)
        .background(gradientBlobBrush(px, px))
  )
}

/**
 * Animated variant: `iTime` phase-shifts the rings outward; `38·d − iTime` over 2π loops
 * seamlessly.
 */
private val GRADIENT_BLOB_ANIMATED_SKSL =
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
    .trimIndent()

/**
 * The animated shader as a GIF: `iTime` ramps 0 → 2π every 2s via `rememberInfiniteTransition`, and
 * the desktop `@AnimatedPreview` path advances the paused clock and encodes `renders/<id>.gif`.
 * `durationMs = 2000` captures exactly one loop (an `InfiniteTransition` has no inherent duration).
 */
@Preview(name = "Runtime Shader — Animated Blob")
@AnimatedPreview(durationMs = 2000, frameIntervalMs = 50, showCurves = false)
@Composable
fun RuntimeShaderAnimatedBlobPreview() {
  val sizeDp = 220.dp
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
  val effect = remember { RuntimeEffect.makeForShader(GRADIENT_BLOB_ANIMATED_SKSL) }
  val builder = remember(effect) { RuntimeShaderBuilder(effect) }
  builder.uniform("iResolution", px, px)
  builder.uniform("iTime", time)
  Box(
    modifier =
      Modifier.size(sizeDp)
        .clip(androidx.compose.ui.graphics.RectangleShape)
        .background(ShaderBrush(builder.makeShader().asComposeShader()))
  )
}
