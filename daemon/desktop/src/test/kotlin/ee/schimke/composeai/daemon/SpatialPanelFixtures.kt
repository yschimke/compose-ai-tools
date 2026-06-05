package ee.schimke.composeai.daemon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Fixture composables for the `spatial-rich` panels — a small "music app", one composable per
// panel.
// They are rendered to PNG textures AND have their real Compose semantics harvested for the
// wireframe
// (see SpatialRichFixtureGeneratorTest). Plain Material3 / foundation so they render on Compose
// Desktop without Android. Each fills its panel; the surface tint keeps the 3D scene colourful.

private fun Color.scale(k: Float) =
  Color(
    (red * k).coerceIn(0f, 1f),
    (green * k).coerceIn(0f, 1f),
    (blue * k).coerceIn(0f, 1f),
    alpha,
  )

@Composable
private fun Panel(base: Color, content: @Composable BoxScope.() -> Unit) {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Box(
      modifier =
        Modifier.fillMaxSize()
          .background(Brush.verticalGradient(listOf(base.scale(1.28f), base.scale(0.94f)))),
      content = content,
    )
  }
}

private val White70 = Color.White.copy(alpha = 0.62f)
private val White50 = Color.White.copy(alpha = 0.5f)

/** A round "vinyl" cover used for the now-playing thumbnail and the album-art panel. */
@Composable
private fun Cover(modifier: Modifier, edge: Color, core: Color, center: Color, radius: Dp) {
  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(radius))
        .background(Brush.linearGradient(listOf(edge.scale(1.4f), edge.scale(0.7f)))),
    contentAlignment = Alignment.Center,
  ) {
    Box(
      Modifier.fillMaxSize(0.72f)
        .clip(CircleShape)
        .background(Brush.radialGradient(listOf(core.scale(1.3f), core.scale(0.4f)))),
      contentAlignment = Alignment.Center,
    ) {
      Box(Modifier.fillMaxSize(0.14f).clip(CircleShape).background(center))
    }
  }
}

@Composable
fun NowPlayingPanel() =
  Panel(Color(0xFF6750A4)) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
      Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Cover(Modifier.size(108.dp), Color(0xFF8E78D6), Color(0xFF2A2440), Color(0xFFB6A6E6), 14.dp)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
          Text(
            "Midnight City",
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
          )
          Spacer(Modifier.height(6.dp))
          Text("M83 · Hurry Up, We're Dreaming", color = White70, fontSize = 18.sp, maxLines = 1)
        }
      }
      Slider(value = 0.62f, onValueChange = {}, modifier = Modifier.fillMaxWidth())
    }
  }

@Composable
fun AlbumArtPanel() =
  Panel(Color(0xFF2178C8)) {
    Cover(
      modifier =
        Modifier.fillMaxSize().padding(16.dp).semantics {
          contentDescription = "Album cover — Hurry Up, We're Dreaming"
        },
      edge = Color(0xFF49B0F0),
      core = Color(0xFF0E1E30),
      center = Color(0xFF7FD0FF),
      radius = 20.dp,
    )
  }

@Composable
private fun QueueRow(track: String) {
  Box(
    Modifier.fillMaxWidth()
      .height(64.dp)
      .clip(RoundedCornerShape(14.dp))
      .background(Color.White.copy(alpha = 0.12f))
      .clickable(onClickLabel = track, role = Role.Button) {}
      .padding(horizontal = 14.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(40.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Brush.linearGradient(listOf(Color(0xFF35D6C8), Color(0xFF0A8278))))
      )
      Spacer(Modifier.width(14.dp))
      Text(track, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
  }
}

@Composable
fun QueuePanel() =
  Panel(Color(0xFF008278)) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
      Text("Up Next", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.height(14.dp))
      for (track in listOf("Outro", "Reunion", "Wait", "Solitude")) {
        QueueRow(track)
        Spacer(Modifier.height(12.dp))
      }
    }
  }

@Composable
fun LyricsPanel() =
  Panel(Color(0xFF963C46)) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
      val lines =
        listOf(
          "Waiting for the sun",
          "to set over the city",
          "the lights go down",
          "and we drive",
          "into the neon haze",
          "of midnight",
        )
      lines.forEachIndexed { i, line ->
        val current = i == 2
        Text(
          line,
          color = if (current) Color.White else White50,
          fontSize = if (current) 26.sp else 22.sp,
          fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(12.dp))
      }
    }
  }

private fun DrawScope.playGlyph(c: Color) {
  val p =
    Path().apply {
      moveTo(size.width * 0.1f, 0f)
      lineTo(size.width, size.height / 2f)
      lineTo(size.width * 0.1f, size.height)
      close()
    }
  drawPath(p, c)
}

private fun DrawScope.skipGlyph(c: Color, back: Boolean) {
  val w = size.width
  val h = size.height
  fun tri(x0: Float, x1: Float) {
    drawPath(
      Path().apply {
        moveTo(x0, 0f)
        lineTo(x1, h / 2f)
        lineTo(x0, h)
        close()
      },
      c,
    )
  }
  if (back) {
    tri(w * 0.55f, w * 0.05f)
    tri(w, w * 0.5f)
  } else {
    tri(0f, w * 0.5f)
    tri(w * 0.45f, w * 0.95f)
  }
}

@Composable
private fun ControlButton(label: String, size: Dp, bg: Color, draw: DrawScope.() -> Unit) {
  Box(
    Modifier.size(size)
      .clip(CircleShape)
      .background(bg)
      .clickable(onClickLabel = label, role = Role.Button) {}
      .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    Canvas(Modifier.fillMaxSize(0.4f)) { draw() }
  }
}

@Composable
fun TransportPanel() =
  Panel(Color(0xFF303844)) {
    Row(
      Modifier.fillMaxSize(),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      ControlButton("Previous", 48.dp, Color.White.copy(alpha = 0.10f)) {
        skipGlyph(Color.White, back = true)
      }
      Spacer(Modifier.width(18.dp))
      ControlButton("Play", 62.dp, Color(0xFF9FB0D0)) { playGlyph(Color(0xFF141418)) }
      Spacer(Modifier.width(18.dp))
      ControlButton("Next", 48.dp, Color.White.copy(alpha = 0.10f)) {
        skipGlyph(Color.White, back = false)
      }
    }
  }

@Composable
fun VolumePanel() =
  Panel(Color(0xFF606E7C)) {
    Box(
      Modifier.fillMaxSize().semantics { contentDescription = "Volume" },
      contentAlignment = Alignment.Center,
    ) {
      Box(
        Modifier.width(12.dp)
          .fillMaxHeight(0.84f)
          .clip(RoundedCornerShape(6.dp))
          .background(Color.White.copy(alpha = 0.22f)),
        contentAlignment = Alignment.BottomCenter,
      ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.62f).background(Color.White))
      }
      Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White))
    }
  }
