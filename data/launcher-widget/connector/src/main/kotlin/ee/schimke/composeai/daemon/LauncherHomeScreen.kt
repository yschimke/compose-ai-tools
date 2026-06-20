package ee.schimke.composeai.daemon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Simulated Android launcher home screen drawn entirely in `androidx.compose.foundation` /
 * `androidx.compose.ui` primitives so it renders identically on the Android (Robolectric) and
 * desktop (Skiko) backends — the connector this lives in is consumed by both daemons and has no
 * `android.*` on its classpath.
 *
 * It is **cosmetic**, in the same spirit as the renderers' `SystemBarsFrame`: nothing here reads
 * real device state. The "9:30" clock, the battery glyph, and the "San Francisco / 67° / Partly
 * Cloudy" weather block are fixed mock values so a captured frame *looks* like a real home-screen
 * screenshot rather than a naked widget on a white rectangle. The app icons are placeholder
 * coloured tiles, not the consumer's real apps.
 *
 * The one real input is the widget itself: [content] is the preview being reviewed, placed on the
 * home screen at the [widgetWidthDp] × [widgetHeightDp] footprint the launcher-widget cell resolver
 * computed. That keeps the existing cell-size / resize machinery the source of truth for how big
 * the widget is — launcher mode only changes what surrounds it, so an existing
 * `@LauncherWidgetPreview` (or a `renderNow.overrides.launcherWidget`) "just works" when the mode
 * flag is flipped on.
 *
 * The chrome fills whatever canvas the render is handed (`Modifier.fillMaxSize()`), so a
 * full-device capture is a matter of requesting a phone-shaped sandbox; on a small sandbox it
 * simply renders a miniature launcher.
 */
@Composable
internal fun LauncherHomeScreen(
  widgetWidthDp: Int,
  widgetHeightDp: Int,
  content: @Composable () -> Unit,
) {
  // Dusk wallpaper gradient — independent of the app's light/dark theme, the way a real launcher
  // wallpaper is. Light foreground reads well against it in either mode.
  val wallpaper =
    Brush.verticalGradient(
      0f to Color(0xFF12233F),
      0.45f to Color(0xFF3A2A5E),
      1f to Color(0xFF7A4E6E),
    )
  val onWallpaper = Color(0xFFF5F5FA)
  val onWallpaperDim = Color(0xCCF5F5FA)

  Box(modifier = Modifier.fillMaxSize().background(wallpaper)) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
      StatusBar(foreground = onWallpaper)
      Spacer(Modifier.height(12.dp))
      WeatherHeader(foreground = onWallpaper, foregroundDim = onWallpaperDim)
      Spacer(Modifier.height(20.dp))

      // The widget tray: the preview placed on the home screen at its resolved cell footprint.
      // A faint scrim behind it means even a fully transparent widget reads as a placed tile.
      Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
          modifier =
            Modifier.size(width = widgetWidthDp.dp, height = widgetHeightDp.dp)
              .clip(RoundedCornerShape(20.dp))
              .background(Color(0x33000000))
        ) {
          content()
        }
      }

      Spacer(Modifier.weight(1f))
      AppGrid(foreground = onWallpaper)
      Spacer(Modifier.height(14.dp))
      PageIndicator(foreground = onWallpaper)
      Spacer(Modifier.height(14.dp))
      Dock()
      Spacer(Modifier.height(10.dp))
      GestureHandle(foreground = onWallpaper)
      Spacer(Modifier.height(6.dp))
    }
  }
}

@Composable
private fun StatusBar(foreground: Color) {
  Row(
    modifier = Modifier.fillMaxWidth().height(24.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(
      text = "9:30",
      style = TextStyle(color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold),
    )
    Spacer(Modifier.weight(1f))
    SignalDots(color = foreground)
    Spacer(Modifier.width(6.dp))
    BatteryGlyph(color = foreground)
  }
}

/** Three rising bars standing in for the mobile-signal indicator. */
@Composable
private fun SignalDots(color: Color) {
  Canvas(modifier = Modifier.size(width = 16.dp, height = 10.dp)) {
    val barW = size.width / 4f
    val gap = barW / 2f
    val heights = floatArrayOf(0.45f, 0.7f, 1f)
    heights.forEachIndexed { i, h ->
      val x = i * (barW + gap)
      val barH = size.height * h
      drawRect(
        color = color,
        topLeft = Offset(x, size.height - barH),
        size = androidx.compose.ui.geometry.Size(barW, barH),
      )
    }
  }
}

/** Compact battery icon — outlined body, terminal nub, ~80% fill. Mirrors the renderers' glyph. */
@Composable
private fun BatteryGlyph(color: Color) {
  Canvas(modifier = Modifier.size(width = 20.dp, height = 10.dp)) {
    val nubW = 1.5.dp.toPx()
    val nubH = size.height * 0.5f
    val cornerR = 2.dp.toPx()
    val strokeW = 1.2.dp.toPx()
    val bodyW = size.width - nubW
    val bodyH = size.height
    drawRoundRect(
      color = color,
      topLeft = Offset(0f, 0f),
      size = androidx.compose.ui.geometry.Size(bodyW, bodyH),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR),
      style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW),
    )
    drawRoundRect(
      color = color,
      topLeft = Offset(bodyW, (bodyH - nubH) / 2f),
      size = androidx.compose.ui.geometry.Size(nubW, nubH),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR / 2f),
    )
    val pad = strokeW + 1f
    val fillW = ((bodyW - pad * 2f) * 0.8f).coerceAtLeast(1f)
    drawRoundRect(
      color = color,
      topLeft = Offset(pad, pad),
      size = androidx.compose.ui.geometry.Size(fillW, bodyH - pad * 2f),
      cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR / 2f),
    )
  }
}

/** "Weather at the top": a sun glyph beside a city / temperature / condition block. Mock values. */
@Composable
private fun WeatherHeader(foreground: Color, foregroundDim: Color) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    SunGlyph()
    Spacer(Modifier.width(14.dp))
    Column {
      BasicText(
        text = "San Francisco",
        style = TextStyle(color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium),
      )
      BasicText(
        text = "67°",
        style = TextStyle(color = foreground, fontSize = 44.sp, fontWeight = FontWeight.Light),
      )
      BasicText(
        text = "Partly Cloudy   H:70°  L:55°",
        style = TextStyle(color = foregroundDim, fontSize = 12.sp),
      )
    }
  }
}

/** A sun: filled disc with eight rays, drawn so the weather block reads at a glance. */
@Composable
private fun SunGlyph() {
  Canvas(modifier = Modifier.size(48.dp)) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val core = size.minDimension * 0.26f
    val rayInner = core * 1.35f
    val rayOuter = size.minDimension * 0.48f
    val sun = Color(0xFFFFD27D)
    drawCircle(color = sun, radius = core, center = center)
    val strokeW = 2.2.dp.toPx()
    for (i in 0 until 8) {
      val a = (Math.PI / 4.0) * i
      val dx = kotlin.math.cos(a).toFloat()
      val dy = kotlin.math.sin(a).toFloat()
      drawLine(
        color = sun,
        start = Offset(center.x + dx * rayInner, center.y + dy * rayInner),
        end = Offset(center.x + dx * rayOuter, center.y + dy * rayOuter),
        strokeWidth = strokeW,
      )
    }
  }
}

/**
 * Placeholder app for the home-screen grid / dock: a coloured rounded-square tile with a single
 * initial, the way a launcher draws an adaptive icon before the real artwork loads.
 */
private data class LauncherApp(val label: String, val initial: String, val color: Color)

private val HOME_APPS =
  listOf(
    LauncherApp("Calendar", "C", Color(0xFFE15B64)),
    LauncherApp("Photos", "P", Color(0xFF4FB477)),
    LauncherApp("Maps", "M", Color(0xFF3D7DCA)),
    LauncherApp("Clock", "T", Color(0xFFEEA236)),
    LauncherApp("Notes", "N", Color(0xFFE9C46A)),
    LauncherApp("Music", "♪", Color(0xFFB565A7)),
    LauncherApp("Files", "F", Color(0xFF5BA8C4)),
    LauncherApp("Wallet", "W", Color(0xFF2A9D8F)),
    LauncherApp("Store", "S", Color(0xFF6C7AE0)),
    LauncherApp("Mail", "@", Color(0xFFD9534F)),
    LauncherApp("Settings", "⚙", Color(0xFF8895A7)),
    LauncherApp("Camera", "◉", Color(0xFF44506B)),
  )

private val DOCK_APPS =
  listOf(
    LauncherApp("Phone", "☎", Color(0xFF4FB477)),
    LauncherApp("Messages", "✉", Color(0xFF3D7DCA)),
    LauncherApp("Chrome", "◐", Color(0xFFE15B64)),
    LauncherApp("Camera", "◉", Color(0xFF44506B)),
  )

private const val GRID_COLUMNS = 4

/** Three rows of placeholder apps laid out on a 4-column grid below the widget. */
@Composable
private fun AppGrid(foreground: Color) {
  Column(modifier = Modifier.fillMaxWidth()) {
    HOME_APPS.chunked(GRID_COLUMNS).forEach { rowApps ->
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        rowApps.forEach { app -> AppIcon(app = app, labelColor = foreground, showLabel = true) }
        // Pad a short final row so the columns stay aligned to the grid.
        repeat(GRID_COLUMNS - rowApps.size) { Spacer(Modifier.width(56.dp)) }
      }
    }
  }
}

@Composable
private fun AppIcon(app: LauncherApp, labelColor: Color, showLabel: Boolean) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(
      modifier = Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(app.color),
      contentAlignment = Alignment.Center,
    ) {
      BasicText(
        text = app.initial,
        style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
      )
    }
    if (showLabel) {
      Spacer(Modifier.height(5.dp))
      Box(modifier = Modifier.width(60.dp), contentAlignment = Alignment.Center) {
        BasicText(
          text = app.label,
          maxLines = 1,
          style = TextStyle(color = labelColor, fontSize = 11.sp, textAlign = TextAlign.Center),
        )
      }
    }
  }
}

/** Page dots, the centre one emphasised — the home-screen-of-many affordance. */
@Composable
private fun PageIndicator(foreground: Color) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    repeat(3) { i ->
      val active = i == 1
      Box(
        modifier =
          Modifier.padding(horizontal = 4.dp)
            .size(if (active) 7.dp else 6.dp)
            .clip(CircleShape)
            .background(if (active) foreground else foreground.copy(alpha = 0.4f))
      )
    }
  }
}

/** The hotseat: a translucent rounded bar of always-present apps. */
@Composable
private fun Dock() {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(28.dp))
        .background(Color(0x33FFFFFF))
        .padding(horizontal = 18.dp, vertical = 12.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    DOCK_APPS.forEach { app ->
      AppIcon(app = app, labelColor = Color.Transparent, showLabel = false)
    }
  }
}

/** Gesture-navigation pill at the very bottom. */
@Composable
private fun GestureHandle(foreground: Color) {
  Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(
      modifier =
        Modifier.size(width = 120.dp, height = 5.dp)
          .clip(RoundedCornerShape(3.dp))
          .background(foreground.copy(alpha = 0.85f))
    )
  }
}
