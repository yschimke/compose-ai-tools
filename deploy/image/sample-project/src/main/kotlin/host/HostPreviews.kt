package host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Foundation-only previews (no material3) so the standalone module stays tiny and
// avoids Compose-MP material3 version skew. These exist purely to give the hosted
// server something to render; replace the served module to host real previews.

@Preview(name = "Badge")
@Composable
fun BadgePreview() {
  Box(
    modifier =
      Modifier.size(220.dp, 96.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF2D6CDF)),
    contentAlignment = Alignment.Center,
  ) {
    BasicText("compose-preview", style = TextStyle(color = Color.White, fontSize = 20.sp))
  }
}

@Preview(name = "Palette")
@Composable
fun PalettePreview() {
  Column(
    modifier = Modifier.background(Color(0xFF0E1116)).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    listOf(0xFF4F86C6, 0xFF63B0CD, 0xFFB8E0D2, 0xFFF6C28B, 0xFFE3655B).forEach { swatch ->
      Box(modifier = Modifier.size(180.dp, 28.dp).clip(RoundedCornerShape(6.dp)).background(Color(swatch)))
    }
  }
}

@Preview(name = "Dots")
@Composable
fun DotsPreview() {
  Row(
    modifier = Modifier.background(Color.White).padding(20.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    listOf(0xFFE3655B, 0xFFF6C28B, 0xFF63B0CD).forEach { c ->
      Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50)).background(Color(c)))
    }
  }
}
