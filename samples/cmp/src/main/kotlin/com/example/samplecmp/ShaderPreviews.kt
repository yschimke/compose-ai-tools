package com.example.samplecmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Compose Multiplatform / Desktop runtime-shader smoke test.
 *
 * This is the CMP-backend half of "shader support" in the preview pipeline. The desktop renderer is
 * `ImageComposeScene` on a skiko **CPU raster** surface ([DesktopRendererMain]), and skiko's
 * [RuntimeEffect] compiles and runs SkSL directly on that surface — no GPU context required. So a
 * `@Preview` whose background is a [ShaderBrush] built from a compiled SkSL program rasterises
 * through the existing pipeline with **zero renderer changes**: it's the low-risk proof that
 * runtime shaders capture to PNG outside Android Studio.
 *
 * The Android counterpart (`android.graphics.RuntimeShader` / AGSL under Robolectric NATIVE
 * graphics) is tracked separately — AGSL is nearly a subset of SkSL, but the capture path runs
 * through Robolectric's native runtime rather than skiko, so it needs its own end-to-end proof.
 *
 * `skiko` (`org.jetbrains.skia.*`) is compile-visible here transitively through
 * `compose.desktop.currentOs`; the same dependency the desktop renderer itself uses to encode PNGs.
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
 * Builds a [ShaderBrush] from [GRADIENT_BLOB_SKSL], feeding the draw-area resolution (in px) as the
 * `iResolution` uniform so the pattern is centred regardless of capture density. The effect and
 * builder are `remember`ed so recomposition doesn't recompile the SkSL.
 */
@Composable
private fun gradientBlobBrush(widthPx: Float, heightPx: Float): Brush {
  val effect = remember { RuntimeEffect.makeForShader(GRADIENT_BLOB_SKSL) }
  val builder = remember(effect) { RuntimeShaderBuilder(effect) }
  builder.uniform("iResolution", widthPx, heightPx)
  return ShaderBrush(builder.makeShader())
}

/**
 * A 220×220dp box filled with the runtime-shader brush. If this captures as a ringed radial
 * gradient (rather than a flat or blank box), the desktop renderer's SkSL path is working end to
 * end.
 */
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
