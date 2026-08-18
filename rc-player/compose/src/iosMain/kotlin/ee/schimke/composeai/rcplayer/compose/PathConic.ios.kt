package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath

internal actual fun Path.conicToSkia(x1: Float, y1: Float, x2: Float, y2: Float, weight: Float) {
  // Skiko m144 deprecated the mutating `Path` API at DeprecationLevel.ERROR in
  // favour of `PathBuilder`. Source-level only, and `PathBuilder`'s
  // build-then-snapshot shape contradicts an `expect` that mutates its receiver
  // — full rationale in `PathConic.jvm.kt`. All three actuals (JVM /
  // wasmJs / ios) must carry this together or the target set diverges.
  @Suppress("DEPRECATION_ERROR") asSkiaPath().conicTo(x1, y1, x2, y2, weight)
}
