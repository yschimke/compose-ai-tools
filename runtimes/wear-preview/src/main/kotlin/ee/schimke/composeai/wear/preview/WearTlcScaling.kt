package ee.schimke.composeai.wear.preview

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec

/**
 * Shows Wear `TransformingLazyColumn` item scaling for one component in an isolated `@Preview`,
 * authored in the exact list-item code. `transformedHeight(this, spec)` and
 * `SurfaceTransformation(spec)` need a sealed [TransformingLazyColumnItemScope] that only a real
 * list provides, so this hosts a real single-item list (padded with spacers so it scrolls) and
 * passes the genuine scope and spec to [content]:
 * ```
 * TlcScalingHost { spec ->
 *   TitleCard(
 *     onClick = {},
 *     modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),  // real Wear API
 *     transformation = SurfaceTransformation(spec),                     // real Wear API
 *   ) { … }
 * }
 * ```
 * At rest the item is centred and unscaled. Let the harness drive the scroll to show scaling:
 * `@ScrollingPreview(modes = [ScrollMode.TOP, ScrollMode.END], reduceMotion = false)` for stills
 * (bound END with `maxScrollPx`), or `modes = [ScrollMode.GIF]` for an animation.
 */
@Composable
fun TlcScalingHost(
  content: @Composable TransformingLazyColumnItemScope.(TransformationSpec) -> Unit
) {
  val screenHeightDp = LocalConfiguration.current.screenHeightDp
  // Anchor the item (index 1, between the spacers) centred at full scale — the resting, no-op
  // state.
  // The capture harness scrolls from here to render the scaled positions.
  val state = rememberTransformingLazyColumnState(initialAnchorItemIndex = 1)
  val spec = rememberTransformationSpec()
  MaterialTheme {
    TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
      // A full-screen spacer above and below gives the item clear room to scroll to any position.
      item { Spacer(Modifier.height(screenHeightDp.dp)) }
      item { content(spec) }
      item { Spacer(Modifier.height(screenHeightDp.dp)) }
    }
  }
}
