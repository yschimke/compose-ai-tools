package ee.schimke.composeai.rcplayer.compose

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asSkiaPath

internal actual fun Path.conicToSkia(x1: Float, y1: Float, x2: Float, y2: Float, weight: Float) {
  asSkiaPath().conicTo(x1, y1, x2, y2, weight)
}
