package com.example.samplecmp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp

/**
 * Demo data for the CMP Desktop `@PreviewParameter` sample. Each value has a
 * distinct label + colour, so the fan-out lands on visually different PNGs —
 * and since [SwatchData] exposes a [label] property, the renderer derives
 * filename suffixes from it (`<id>_Crimson.png`, `<id>_Teal.png`, …) instead
 * of opaque `_PARAM_<idx>` indices.
 */
data class SwatchData(val label: String, val color: Long)

class SwatchProvider : PreviewParameterProvider<SwatchData> {
    override val values: Sequence<SwatchData> = sequenceOf(
        SwatchData("Crimson", 0xFFDC143C),
        SwatchData("Teal", 0xFF008B8B),
        SwatchData("Amber", 0xFFFFBF00),
        SwatchData("Violet", 0xFF7F00FF),
    )
}

/**
 * Desktop (CMP) `@PreviewParameter` smoke test. The JVM renderer consumes
 * the provider FQN + limit passed on the command line, loops over
 * `values.take(limit)` inside a single process, and emits one PNG per
 * value — keyed by the value's derived label (from a `name`/`label`/`id`
 * property or a `Pair`'s `first`) and falling back to `_PARAM_<idx>` when
 * no label can be derived.
 */
@Preview(name = "Color Swatch", backgroundColor = 0xFFFFFFFF, showBackground = true)
@Composable
fun SwatchPreview(
    @PreviewParameter(SwatchProvider::class) swatch: SwatchData,
) {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(160.dp, 80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(swatch.color.toInt())),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = swatch.label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
