package com.example.sampleandroid

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

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
