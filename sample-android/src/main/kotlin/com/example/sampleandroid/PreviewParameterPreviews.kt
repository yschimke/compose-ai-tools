package com.example.sampleandroid

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp

/**
 * Demo data for the `@PreviewParameter` samples below. Shape mirrors what a
 * typical list-cell or detail-screen composable would receive from a ViewModel
 * — a handful of fields, each value visually distinct, so the rendered PNGs
 * make the fan-out obvious at a glance.
 */
data class UserCardData(
    val name: String,
    val role: String,
    val active: Boolean,
)

/**
 * Minimal `@PreviewParameter` demo: one preview function, one provider, N
 * rendered PNGs. The plugin's discovery pass records the provider FQN on
 * `PreviewParams`; the Robolectric renderer instantiates the provider at
 * test-load time, enumerates `values`, and emits one file per value. The
 * filename suffix is derived from the value's `name` property
 * (`..._Ada_Lovelace.png`, `..._Grace_Hopper.png`, …); the renderer falls
 * back to `_PARAM_<idx>` only when no label can be recovered.
 *
 * Kept intentionally simple (no `@PreviewWrapper`, no device, no fan-out
 * dimensions other than the provider) so the output diff reviewing the
 * parameter path is unambiguous.
 */
class UserCardProvider : PreviewParameterProvider<UserCardData> {
    override val values: Sequence<UserCardData> = sequenceOf(
        UserCardData("Ada Lovelace", "Principal Engineer", active = true),
        UserCardData("Grace Hopper", "Distinguished Engineer", active = true),
        UserCardData("Alan Turing", "Research Fellow", active = false),
    )
}

@Preview(name = "User Card", showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 260)
@Composable
fun UserCardPreview(
    @PreviewParameter(UserCardProvider::class) user: UserCardData,
) {
    MaterialTheme {
        Card(modifier = Modifier.padding(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(user.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(user.role, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (user.active) "● Active" else "○ Inactive",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (user.active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Demonstrates `limit = N` on `@PreviewParameter`: the provider exposes seven
 * values, but the annotation takes only the first three. Good smoke test for
 * the `limit` handling — without it the plugin would render seven PNGs here,
 * one of which (`status = "unknown"`) would look odd next to the rest.
 */
class TextSampleProvider : PreviewParameterProvider<String> {
    override val values: Sequence<String> = sequenceOf(
        "The quick brown fox",
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit.",
        "Short",
        "A moderately long line of body text, wrapping naturally across the available width.",
        "ALL CAPS FOR EMPHASIS",
        "Line with a trailing ellipsis…",
        "unused — beyond the limit",
    )
}

@Preview(name = "Body Text", showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 320)
@Composable
fun BodyTextPreview(
    @PreviewParameter(TextSampleProvider::class, limit = 3) body: String,
) {
    MaterialTheme {
        Text(
            text = body,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
