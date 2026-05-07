package com.example.samplealpha

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.preview.AnimatedPreview
import kotlinx.coroutines.delay

private val LABELS = listOf("Save", "Edit", "Share", "Delete")

@Composable
private fun ButtonRow(focusedIndex: Int) {
    val frs = remember { List(LABELS.size) { FocusRequester() } }
    LaunchedEffect(focusedIndex) { frs[focusedIndex].requestFocus() }
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        LABELS.forEachIndexed { i, label ->
            Button(
                onClick = {},
                modifier = Modifier.padding(end = 8.dp).focusRequester(frs[i]),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(label)
            }
        }
    }
}

class FocusIndexProvider : PreviewParameterProvider<Int> {
    override val values: Sequence<Int> = (0 until LABELS.size).asSequence()
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
@Composable
fun InsetFocusRingFanOutPreview(@PreviewParameter(FocusIndexProvider::class) focusedIndex: Int) {
    MaterialTheme {
        WithRippleConfig(RippleDefaults.InsetFocusRingRippleThemeConfiguration) {
            ButtonRow(focusedIndex)
        }
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
        WithRippleConfig(RippleDefaults.InsetFocusRingRippleThemeConfiguration) { ButtonRow(idx) }
    }
}

@Preview(
    name = "Opacity vs Inset Ring",
    widthDp = 480,
    heightDp = 192,
    showBackground = true,
)
@Composable
fun OpacityVsInsetRingPreview() {
    MaterialTheme {
        Column {
            WithRippleConfig(RippleDefaults.OpacityFocusRippleThemeConfiguration) {
                ButtonRow(focusedIndex = 1)
            }
            WithRippleConfig(RippleDefaults.InsetFocusRingRippleThemeConfiguration) {
                ButtonRow(focusedIndex = 1)
            }
        }
    }
}
