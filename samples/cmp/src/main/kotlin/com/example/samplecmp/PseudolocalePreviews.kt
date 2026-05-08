package com.example.samplecmp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun PseudoBidiBody() {
  Surface(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(shape = CircleShape, color = Color(0xFF6750A4), modifier = Modifier.size(32.dp)) {}
      Text("Inbox", modifier = Modifier.weight(1f))
      Text("12")
    }
  }
}

@Preview(name = "default", showBackground = true, widthDp = 320, heightDp = 80)
@Composable
fun CmpPseudoDefault() {
  PseudoBidiBody()
}

// Desktop pseudolocale support is layout-direction-only — `ar-XB` flips the row so the avatar
// ends up on the right and the count sits on the left. Text content isn't pseudolocalised on
// CMP because `org.jetbrains.compose.resources` doesn't go through `LocalContext.resources`.
// See the platform-support note in `site/reference/pseudolocale.md`.
@Preview(name = "bidi", locale = "ar-XB", showBackground = true, widthDp = 320, heightDp = 80)
@Composable
fun CmpPseudoBidi() {
  PseudoBidiBody()
}
