package com.example.sdkmatrix

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The single `@Preview` the SDK-compatibility matrix workflow exercises. The preview body reads
 * what Robolectric and AGP actually surface at render time — `Build.VERSION.SDK_INT` (the
 * synthesized framework level, driven by `sdk=N` in the generated `robolectric.properties`),
 * `Build.VERSION.RELEASE` (the matching Android release codename), and
 * `applicationInfo.targetSdkVersion` (what AGP stamped into the manifest from
 * `composeai.matrix.targetSdk`) — and renders them as text. The captured PNG is therefore
 * self-documenting: each matrix cell's artifact literally shows the observed values for that
 * cell. The nightly aggregator just reads the cells' pass/fail state and surfaces the PNGs.
 */
@Preview(name = "SdkMatrixPreview", widthDp = 280, heightDp = 160)
@Composable
fun SdkMatrixPreview() {
  val context = LocalContext.current
  val observedTarget = context.applicationInfo.targetSdkVersion
  Column(
    modifier = Modifier.size(280.dp, 160.dp).background(Color(0xFF1B1B1F)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = "sdk-matrix",
      color = Color(0xFFCFBCFF),
      fontSize = 14.sp,
      style = MaterialTheme.typography.titleSmall,
    )
    Text(
      text = "Build.VERSION.SDK_INT = ${Build.VERSION.SDK_INT}",
      color = Color.White,
      fontSize = 12.sp,
    )
    Text(
      text = "Build.VERSION.RELEASE = ${Build.VERSION.RELEASE}",
      color = Color.White,
      fontSize = 12.sp,
    )
    Text(text = "targetSdkVersion = $observedTarget", color = Color.White, fontSize = 12.sp)
  }
}
