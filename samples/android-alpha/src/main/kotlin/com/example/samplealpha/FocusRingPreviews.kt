package com.example.samplealpha

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LocalRippleThemeConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleDefaults
import androidx.compose.material3.RippleThemeConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.FocusDirection
import ee.schimke.composeai.preview.FocusedPreview

private val LABELS = listOf("Save", "Edit", "Share", "Delete")

@Composable
private fun ButtonRow() {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        LABELS.forEach { label ->
            Button(
                onClick = {},
                modifier = Modifier.padding(end = 8.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun WithRippleConfig(config: RippleThemeConfiguration, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRippleThemeConfiguration provides config, content = content)
}

@Preview(
    name = "Inset Focus Ring — fan-out",
    widthDp = 480,
    heightDp = 96,
    showBackground = true,
)
@FocusedPreview(indices = [0, 1, 2, 3])
@Composable
fun InsetFocusRingFanOutPreview() {
    MaterialTheme {
        WithRippleConfig(RippleDefaults.InsetFocusRingRippleThemeConfiguration) { ButtonRow() }
    }
}

/**
 * Moving inset focus ring as a single animated GIF. `@FocusedPreview(gif = true)` drives focus
 * through the same `FocusManager.moveFocus` path the per-PNG fan-out uses and stitches the
 * per-step captures into a GIF — so the sample stays plain `Row { Button(...) }` with no
 * `MutableInteractionSource` / `LaunchedEffect` focus-emission hacks (which lose the
 * `FocusInteraction.Unfocus` pairing and leave every visited button with a stale focus ring,
 * see #1020).
 */
@Preview(
    name = "Inset Focus Ring — moving",
    widthDp = 480,
    heightDp = 96,
    showBackground = true,
)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun InsetFocusRingMovingPreview() {
    MaterialTheme {
        WithRippleConfig(RippleDefaults.InsetFocusRingRippleThemeConfiguration) { ButtonRow() }
    }
}

/**
 * Opacity-focus baseline: the same row drawn under
 * `RippleDefaults.OpacityFocusRippleThemeConfiguration`. Pair-render with
 * the inset-ring fan-out to see the visual delta between the two
 * focus-indication strategies.
 */
@Preview(
    name = "Opacity Focus",
    widthDp = 480,
    heightDp = 96,
    showBackground = true,
)
@FocusedPreview(indices = [1])
@Composable
fun OpacityFocusPreview() {
    MaterialTheme {
        WithRippleConfig(RippleDefaults.OpacityFocusRippleThemeConfiguration) { ButtonRow() }
    }
}

/**
 * Traversal demo: each capture shows where focus lands after applying that step's direction. The
 * sequence walks Tab → Tab → Shift-Tab → Tab, so Save → Edit → Share → Edit → Share. Useful for
 * asserting keyboard-nav order in PR diffs — each step is a separate PNG so reviewers see exactly
 * which focusable each move targets.
 */
@Preview(
    name = "Focus Traversal",
    widthDp = 480,
    heightDp = 96,
    showBackground = true,
)
@FocusedPreview(
    traverse =
        [FocusDirection.Next, FocusDirection.Next, FocusDirection.Previous, FocusDirection.Next],
)
@Composable
fun FocusTraversalPreview() {
    MaterialTheme {
        WithRippleConfig(RippleDefaults.InsetFocusRingRippleThemeConfiguration) { ButtonRow() }
    }
}

/**
 * Overlay demo: same fan-out as [InsetFocusRingFanOutPreview] but with `overlay = true`. Each PNG
 * carries a labelled stroke around the focused button so review tools can see which button is
 * "supposed to" be focused regardless of whether the indication itself rendered correctly. The
 * pre-overlay capture is preserved alongside as `<basename>.raw.png`.
 */
@Preview(
    name = "Focus Overlay",
    widthDp = 480,
    heightDp = 96,
    showBackground = true,
)
@FocusedPreview(indices = [0, 1, 2, 3], overlay = true)
@Composable
fun FocusOverlayPreview() {
    MaterialTheme {
        WithRippleConfig(RippleDefaults.InsetFocusRingRippleThemeConfiguration) { ButtonRow() }
    }
}
