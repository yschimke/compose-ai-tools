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
 * Shows Wear `TransformingLazyColumn` (TLC) item scaling — scaled and faded toward the curved edges —
 * for a single component in an isolated `@Preview`, with the component authored in the **exact normal
 * list-item code**.
 *
 * Both `Modifier.transformedHeight(this, spec)` and `SurfaceTransformation(spec)` need a
 * [TransformingLazyColumnItemScope] (the `this`), which is `sealed` and only exists inside a real
 * `TransformingLazyColumn`. (A bare [rememberTransformationSpec] gives you the spec but not that
 * scope.) So [TlcScalingHost] hosts a real single-item list — the item flanked by tall spacer items
 * so it can genuinely scroll — and hands its **genuine** scope + spec to [content]:
 * ```
 * TlcScalingHost { spec ->
 *   TitleCard(
 *     onClick = {},
 *     modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),  // real Wear API
 *     transformation = SurfaceTransformation(spec),                     // real Wear API
 *   ) { … }
 * }
 * ```
 *
 * The item rests **centred at full scale** — a plain `@Preview` renders the unscaled resting state,
 * doing nothing special. To show the scaling, let the capture **harness drive the scroll** rather
 * than pinning a position in the preview:
 * - **Stills** — `@ScrollingPreview(modes = [ScrollMode.TOP, ScrollMode.END], reduceMotion = false)`.
 *   `TOP` captures the centred, unscaled frame; `END` (bound it with `maxScrollPx`) rides the item
 *   up into the top scaling zone so it renders scaled + faded. One preview, two states, harness-
 *   controlled.
 * - **A scaling GIF** — `@ScrollingPreview(modes = [ScrollMode.GIF], reduceMotion = false)` animates
 *   the item riding through the viewport.
 */
@Composable
fun TlcScalingHost(content: @Composable TransformingLazyColumnItemScope.(TransformationSpec) -> Unit) {
  val screenHeightDp = LocalConfiguration.current.screenHeightDp
  // Anchor the item (index 1, between the spacers) centred at full scale — the resting, no-op state.
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
