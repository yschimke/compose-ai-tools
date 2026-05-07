package com.example.samplewear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.withSaveLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices

/**
 * Body for the ambient-aware demo. Reads its state from
 * [LocalAmbientModeManager] — the same composition-local seam
 * `androidx.wear.compose.foundation.samples.AmbientModeBasicSample` consumes via
 * `rememberAmbientModeManager()`. The `:data-ambient-connector`'s
 * `AmbientOverrideExtension` (an `AroundComposable` data extension planned from
 * `renderNow.overrides.ambient`) installs the manager backed by
 * `AmbientStateController`, so daemon-driven renders see the override and a real
 * activity runs `rememberAmbientModeManager()` against the on-device Wear Services
 * SDK; preview rendering without an in-flight override falls back to
 * [AmbientMode.Interactive].
 *
 * Visual treatment for the ambient state — desaturated greyscale with a 0.9× scale —
 * is borrowed from horologist's `AmbientAwareActivity` sample
 * (`com.google.android.horologist.ambient.ambientGray`), reimplemented here as a
 * local [Modifier.ambientGray] so this module doesn't pull in horologist just for
 * the styling.
 */
@Composable
fun AmbientStatusBody(now: () -> Long = System::currentTimeMillis) {
  val ambientMode = LocalAmbientModeManager.current?.currentAmbientMode ?: AmbientMode.Interactive
  val isAmbient = ambientMode is AmbientMode.Ambient
  val background = if (isAmbient) Color.Black else Color(0xFF101820)
  val textColor = if (isAmbient) Color(0xFFCFD8DC) else Color.White

  Box(
    modifier = Modifier.fillMaxSize().background(background).ambientGray(ambientMode).padding(16.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(
        text = ambientMode.label(),
        style = MaterialTheme.typography.titleMedium,
        color = textColor,
      )
      Text(text = "t=${now()}", style = MaterialTheme.typography.labelSmall, color = textColor)
    }
  }
}

private fun AmbientMode.label(): String =
  when (this) {
    is AmbientMode.Interactive -> "Interactive"
    is AmbientMode.Ambient -> {
      val flags = buildList {
        if (isBurnInProtectionRequired) add("burnIn")
        if (isLowBitAmbientSupported) add("lowBit")
      }
      if (flags.isEmpty()) "Ambient" else "Ambient(${flags.joinToString(",")})"
    }
    else -> "Unknown"
  }

private val grayscalePaint =
  Paint().apply {
    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    isAntiAlias = false
  }

private fun Modifier.ambientGray(ambientMode: AmbientMode): Modifier =
  if (ambientMode is AmbientMode.Ambient) {
    graphicsLayer {
        scaleX = 0.9f
        scaleY = 0.9f
      }
      .drawWithContent {
        drawIntoCanvas { canvas ->
          canvas.withSaveLayer(size.toRect(), grayscalePaint) { drawContent() }
        }
      }
  } else {
    this
  }

@Preview(name = "Ambient body", device = WearDevices.LARGE_ROUND, showBackground = true)
@Composable
fun AmbientStatusPreview() {
  AmbientStatusBody(now = { 1_700_000_000_000L })
}
