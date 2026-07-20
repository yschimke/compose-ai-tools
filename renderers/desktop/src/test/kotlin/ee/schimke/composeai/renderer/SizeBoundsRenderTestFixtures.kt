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

/**
 * Fixture for the "min bound reaches the component" case. Its root is a **wrap-content** `Box` with
 * a green background and a 56.dp red badge — no `fillMaxSize`, no fixed size — so its intrinsic
 * size is the 56.dp badge (112 px at density 2). When a min-size bound is applied, a
 * correctly-plumbed renderer propagates that min onto this root box, so the green background fills
 * the whole requested area; the buggy behaviour (min lands only on the renderer's wrapper) leaves
 * the green at 56.dp in the corner with the rest of the frame showing the harness background. A
 * pixel deep inside the frame is therefore green iff the component itself took the min size.
 */
@Composable
fun MinFillSticker() {
  Box(modifier = Modifier.background(Color(0xFF1B5E20))) {
    Box(modifier = Modifier.size(56.dp).background(Color(0xFFB71C1C), RoundedCornerShape(28.dp)))
  }
}
