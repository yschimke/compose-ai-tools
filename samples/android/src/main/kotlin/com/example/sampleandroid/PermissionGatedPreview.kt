package com.example.sampleandroid

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ee.schimke.composeai.preview.PermissionPreview

/**
 * Demo of the `data/permissions` extension. The screen calls plain
 * `ContextCompat.checkSelfPermission(...)` (no `granted` parameter), so the previewed code is what
 * ships and the environment supplies the grant.
 *
 * Denied is the resting Robolectric state. The granted preview carries `@PermissionPreview`, which
 * seeds `ShadowApplication`'s grants before first composition; the daemon does the same from
 * `renderNow.overrides.permissions`. The connector also records each query into
 * `compose/permissions`.
 */
@Preview(name = "Camera permission — denied", showBackground = true)
@Composable
fun CameraPermissionDeniedPreview() {
  PermissionGatedCameraScreen()
}

/**
 * The granted branch. `@PermissionPreview` takes the full constant string
 * (`android.permission.CAMERA`) and its grant map is exhaustive. `PermissionPreviewPixelTest`
 * asserts this differs from the denied render.
 */
@Preview(name = "Camera permission — granted", showBackground = true)
@PermissionPreview(grants = ["android.permission.CAMERA=granted"])
@Composable
fun CameraPermissionGrantedPreview() {
  PermissionGatedCameraScreen()
}

@Composable
private fun PermissionGatedCameraScreen() {
  val context = LocalContext.current
  val granted =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED
  Surface(color = MaterialTheme.colorScheme.background) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("Camera access", style = MaterialTheme.typography.titleMedium)
      if (granted) {
        Text(
          "Camera permission granted — viewfinder would render here.",
          style = MaterialTheme.typography.bodyMedium,
        )
      } else {
        Text(
          "We need camera permission to capture photos.",
          style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = {}, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
          Text("Grant camera access")
        }
      }
    }
  }
}
