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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview
import ee.schimke.composeai.preview.FocusedPreview
import kotlinx.coroutines.delay

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

@Preview(
    name = "Inset Focus Ring — moving",
    widthDp = 480,
    heightDp = 96,
    showBackground = true,
)
@AnimatedPreview(durationMs = 3200, frameIntervalMs = 200, showCurves = false)
@Composable
fun InsetFocusRingAnimatedPreview() {
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(800)
            idx = (idx + 1) % LABELS.size
        }
    }
    MaterialTheme {
        WithRippleConfig(RippleDefaults.InsetFocusRingRippleThemeConfiguration) {
            // The animated preview drives focus via in-composition state
            // rather than the @FocusedPreview annotation: the GIF needs
            // one row composable that re-renders its focus state across
            // 16 frames, not 16 separate preview captures.
            FocusRingRowAnimated(idx)
        }
    }
}

@Composable
private fun FocusRingRowAnimated(focusedIndex: Int) {
    val sources =
        remember {
            List(LABELS.size) {
                androidx.compose.foundation.interaction.MutableInteractionSource()
            }
        }
    LaunchedEffect(focusedIndex) {
        sources[focusedIndex].emit(
            androidx.compose.foundation.interaction.FocusInteraction.Focus()
        )
    }
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        LABELS.forEachIndexed { i, label ->
            Button(
                onClick = {},
                modifier = Modifier.padding(end = 8.dp),
                shape = RoundedCornerShape(12.dp),
                interactionSource = sources[i],
            ) {
                Text(label)
            }
        }
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
