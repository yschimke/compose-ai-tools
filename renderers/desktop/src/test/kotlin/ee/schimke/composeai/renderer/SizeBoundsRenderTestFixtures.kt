package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Wrap-content sticker fixture for [DesktopSizeBoundsRendererTest], mirroring the daemon's
 * `WrapContentStickerPreview`: a 56.dp badge in 16.dp padding with **no `fillMaxSize`**, so its
 * intrinsic size is 88.dp on both axes — 176 px at density 2, far smaller than the wrap sandbox.
 * Top-level so the renderer can reflect it the way it reflects a consumer's `@Preview`
 * (`Class.forName("…SizeBoundsRenderTestFixturesKt")` + `getDeclaredComposableMethod`).
 */
@Composable
fun WrapContentSticker() {
  Box(modifier = Modifier.padding(16.dp)) {
    Box(modifier = Modifier.size(56.dp).background(Color(0xFFB71C1C), RoundedCornerShape(28.dp)))
  }
}
