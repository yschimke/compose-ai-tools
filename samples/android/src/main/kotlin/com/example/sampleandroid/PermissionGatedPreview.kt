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

/**
 * Demo of the `data/permissions` data extension. The screen uses the standard Android
 * `ContextCompat.checkSelfPermission(...)` API — there is no connector-specific Compose API to
 * learn. Under the daemon the connector seeds Robolectric's `ShadowApplication` from
 * `renderNow.overrides.permissions`, so the same `checkSelfPermission` call returns the value the
 * agent picked. The connector's `ShadowContextWrapperPermissionTracker` also records each query
 * into the `compose/permissions` payload, so the panel can list what the screen asked about.
 *
 * Flip the chip in Controls to render under a different grant state — the next composition's
 * `checkSelfPermission` read picks up the change through the platform path.
 */
@Preview(name = "Camera permission — denied", showBackground = true)
@Composable
fun CameraPermissionDeniedPreview() {
  PermissionGatedCameraScreen()
}

@Preview(name = "Camera permission — granted", showBackground = true)
@Composable
fun CameraPermissionGrantedPreview() {
  // The granted variant relies on the agent pushing `renderNow.overrides.permissions` with
  // `CAMERA = GRANTED`; without that, both previews render the "needs permission" branch (which
  // is the right shape for a static `@Preview` capture in the build). The variant exists so the
  // panel can pin a label to the granted-state capture once an override is applied.
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
