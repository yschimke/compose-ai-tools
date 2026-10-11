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
 * Demo data for the `@PreviewParameter` samples below: a few visually distinct fields, like a
 * ViewModel would hand a list cell.
 */
data class UserCardData(val name: String, val role: String, val active: Boolean)

/**
 * Minimal `@PreviewParameter` demo: one function, one provider, one PNG per value. The renderer
 * instantiates the provider and names each file from the value's `name` (`..._Ada_Lovelace.png`),
 * falling back to `_PARAM_<idx>`.
 */
class UserCardProvider : PreviewParameterProvider<UserCardData> {
  override val values: Sequence<UserCardData> =
    sequenceOf(
      UserCardData("Ada Lovelace", "Principal Engineer", active = true),
      UserCardData("Grace Hopper", "Distinguished Engineer", active = true),
      UserCardData("Alan Turing", "Research Fellow", active = false),
    )
}

@Preview(name = "User Card", showBackground = true, backgroundColor = 0xFFFFFFFF, widthDp = 260)
@Composable
fun UserCardPreview(@PreviewParameter(UserCardProvider::class) user: UserCardData) {
  MaterialTheme {
    Card(modifier = Modifier.padding(12.dp)) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text(
          user.name,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(user.role, style = MaterialTheme.typography.bodyMedium)
        Text(
          if (user.active) "● Active" else "○ Inactive",
          style = MaterialTheme.typography.labelSmall,
          color =
            if (user.active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * `limit = N` on `@PreviewParameter`: the provider has seven values, the annotation takes the first
 * three.
 */
class TextSampleProvider : PreviewParameterProvider<String> {
  override val values: Sequence<String> =
    sequenceOf(
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
fun BodyTextPreview(@PreviewParameter(TextSampleProvider::class, limit = 3) body: String) {
  MaterialTheme {
    Text(
      text = body,
      modifier = Modifier.padding(16.dp),
      style = MaterialTheme.typography.bodyLarge,
    )
  }
}

/**
 * Regression fixture: a `private` provider compiles to a package-private JVM class, so the renderer
 * must open its constructor and `getValues()` reflectively.
 */
private class BadgeProvider : PreviewParameterProvider<String> {
  override val values: Sequence<String> = sequenceOf("NEW", "BETA", "PRO")
}

@Preview(
  name = "Private Provider Badge",
  showBackground = true,
  backgroundColor = 0xFFFFFFFF,
  widthDp = 200,
)
@Composable
fun PrivateProviderBadgePreview(@PreviewParameter(BadgeProvider::class) label: String) {
  MaterialTheme {
    Card(modifier = Modifier.padding(12.dp)) {
      Text(
        text = label,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}
