package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.overrides.previewOverrideColor

/**
 * A preview whose entire appearance is one `@OverrideVariant`-seedable knob, so a seed leaking from
 * a previous render is visible as a different picture rather than a subtle difference.
 *
 * Used by [DesktopRendererReentrancyTest] to prove that a render is independent of what the process
 * drew before it — the property a pooled renderer worker depends on and a fresh-JVM-per-capture
 * caller got for free.
 */
@Composable
fun OverrideLeakSticker() {
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(previewOverrideColor(key = LEAK_KNOB_KEY, default = Color(0xFF1B5E20)))
  )
}

/** Seed key the fixture reads; the test seeds it to a colour far from the default. */
const val LEAK_KNOB_KEY: String = "leakProbe"
