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
 * Demo of the `data/permissions` data extension. The screen uses the standard Android
 * `ContextCompat.checkSelfPermission(...)` API — there is no connector-specific Compose API to
 * learn, and deliberately no `granted: Boolean` parameter: the previewed code is the code the app
 * ships, and the grant state is supplied by the environment behind the platform call.
 *
 * Both branches are captured statically. The denied preview is the resting off-device state (no
 * permission is granted to a Robolectric application that never asked for one); the granted preview
 * carries `@PermissionPreview`, which the Gradle render lane turns into a
 * `PermissionsOverrideExtension` that seeds Robolectric's `ShadowApplication` grant set before the
 * first composition, so the same `checkSelfPermission` call returns `PERMISSION_GRANTED`.
 *
 * The daemon reaches that identical seam from the other direction:
 * `renderNow.overrides.permissions` plans the same extension, so flipping the chip in Controls
 * re-renders a held preview under a different grant state without the annotation. The connector's
 * `ShadowContextWrapperPermissionTracker` also records each query into the `compose/permissions`
 * payload, so the panel can list what the screen asked about.
 */
@Preview(name = "Camera permission — denied", showBackground = true)
@Composable
fun CameraPermissionDeniedPreview() {
  PermissionGatedCameraScreen()
}

/**
 * The granted branch, captured by the static build (issue #3676). `@PermissionPreview` names the
 * full Android constant string — `Manifest.permission.CAMERA` resolves to
 * `"android.permission.CAMERA"`, which is the key `checkSelfPermission` is queried with — and the
 * grant map is exhaustive, so nothing else is granted for this capture.
 *
 * Its render is the fixture that keeps the two branches honest: `PermissionPreviewPixelTest`
 * asserts this PNG differs from the denied one, so a regression that drops the grant seeding shows
 * up as a failing test rather than as two identical images, one of them mislabelled.
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
