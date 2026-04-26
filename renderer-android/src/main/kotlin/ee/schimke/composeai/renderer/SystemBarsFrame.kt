package ee.schimke.composeai.renderer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Synthetic Android phone system UI: status bar at the top, gesture-pill
 * navigation bar at the bottom. Wraps a composable so it renders inside
 * something that *looks* like a phone screenshot rather than a naked
 * rectangle.
 *
 * Why this exists: Robolectric runs the test without the SystemUI process
 * that paints real bars on-device, so a `@Preview(device = "id:pixel_8",
 * showSystemUi = true)` capture comes back at the right canvas size but with
 * no chrome (issue #256). Android Studio's preview pane closes the same gap
 * via LayoutLib's frame overlay; we draw the equivalent in Compose code
 * inside the same composition the preview itself runs in — no Java2D
 * post-processing — so the bars participate in capture naturally.
 *
 * Three usage shapes, in order of how invasive they are:
 *   1. **Implicit (default)** — set `showSystemUi = true` on a phone-shape
 *      `@Preview`. The renderer wraps the composition in this frame
 *      automatically. Skipped for round Wear devices and tile previews —
 *      the circular clip / tile renderers already brand those captures.
 *   2. **Annotation-driven** — apply `@PreviewWrapper(SystemBarsPreviewWrapper::class)`
 *      to a preview function. Useful when you want phone-shape chrome
 *      around a component preview that doesn't pin a device, or when
 *      composing multiple wrappers. Requires Compose 1.11+ on the consumer
 *      classpath (where `@PreviewWrapper` lives).
 *   3. **Explicit** — call `SystemBarsFrame { … }` directly from a
 *      composable. No annotations needed; works on any Compose version that
 *      has the basic `Box` / `Row` / `Canvas` primitives this composable
 *      uses (1.0+). Useful when you want bar chrome inside an app screen
 *      shot or other in-app context.
 *
 * Style choices:
 *  - Bars are translucent overlays on top of the content (`Box` overlay,
 *    not a `Column` that resizes the body), matching modern edge-to-edge
 *    Android behaviour. No content is reserved out from under the bars.
 *  - 24dp status bar with a "9:30" clock on the left and a small battery
 *    glyph on the right.
 *  - 24dp navigation bar with a centred gesture pill (~108×4dp).
 *  - Light/dark tint chosen from the [uiMode] mask so a `night` capture
 *    gets dark chrome with light icons rather than always-light overlays
 *    on top of a dark surface.
 *
 * @param uiMode Configuration UI-mode bits. Only the `UI_MODE_NIGHT_*` bits
 *     are inspected — pass `Configuration.UI_MODE_NIGHT_YES` (or `0` for
 *     light) when calling from app code; the renderer threads through
 *     `params.uiMode` from the discovered `@Preview` annotation.
 */
@Composable
fun SystemBarsFrame(
    uiMode: Int,
    content: @Composable () -> Unit,
) {
    val night = (uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES
    val barTint = if (night) Color(0x70000000) else Color(0x70FFFFFF)
    val foreground = if (night) Color(0xFFEBEBEB) else Color(0xFF141414)

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        StatusBar(
            modifier = Modifier.align(Alignment.TopCenter),
            tint = barTint,
            foreground = foreground,
        )
        NavigationPillBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            tint = barTint,
            foreground = foreground,
        )
    }
}

@Composable
private fun StatusBar(
    modifier: Modifier,
    tint: Color,
    foreground: Color,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(STATUS_BAR_HEIGHT_DP.dp)
            .background(tint)
            .padding(horizontal = SIDE_INSET_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "9:30",
            style = TextStyle(
                color = foreground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(modifier = Modifier.weight(1f))
        BatteryGlyph(color = foreground)
    }
}

/**
 * Compact battery icon — outlined body with a terminal nub and a ~75% fill.
 * Drawn with `Canvas` rather than nested `Box`es so the stroke + fill can
 * share one drawing pass.
 */
@Composable
private fun BatteryGlyph(color: Color) {
    Canvas(
        modifier = Modifier
            .width(BATTERY_WIDTH_DP.dp)
            .height(BATTERY_HEIGHT_DP.dp),
    ) {
        val nubW = 1.5.dp.toPx()
        val nubH = size.height * 0.5f
        val cornerR = 1.5.dp.toPx()
        val strokeW = 1.dp.toPx()
        val bodyW = size.width - nubW
        val bodyH = size.height
        // Outline — drawn as stroke, leaves the centre transparent so the
        // background tint shows through.
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
            size = androidx.compose.ui.geometry.Size(bodyW, bodyH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeW),
        )
        // Terminal nub.
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(bodyW, (bodyH - nubH) / 2f),
            size = androidx.compose.ui.geometry.Size(nubW, nubH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR / 2f),
        )
        // ~75% charge fill so the glyph reads as a battery rather than an
        // empty rectangle.
        val pad = strokeW + 0.5f
        val fillW = ((bodyW - pad * 2f) * 0.75f).coerceAtLeast(1f)
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
            size = androidx.compose.ui.geometry.Size(fillW, bodyH - pad * 2f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR / 2f),
        )
    }
}

@Composable
private fun NavigationPillBar(
    modifier: Modifier,
    tint: Color,
    foreground: Color,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(NAV_BAR_HEIGHT_DP.dp)
            .background(tint),
        contentAlignment = Alignment.Center,
    ) {
        // Gesture handle: rounded pill centred horizontally; sits visually a
        // hair lower than the bar's centre to match Android's placement.
        Box(
            modifier = Modifier
                .padding(top = (NAV_BAR_HEIGHT_DP / 6).dp)
                .size(width = PILL_WIDTH_DP.dp, height = PILL_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(PILL_HEIGHT_DP.dp / 2))
                .background(foreground)
                .fillMaxHeight(),
        )
    }
}

private const val STATUS_BAR_HEIGHT_DP = 24
private const val NAV_BAR_HEIGHT_DP = 24
private const val SIDE_INSET_DP = 16
private const val BATTERY_WIDTH_DP = 16
private const val BATTERY_HEIGHT_DP = 8
private const val PILL_WIDTH_DP = 108
private const val PILL_HEIGHT_DP = 4

/**
 * `(uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES` — kept as raw
 * constants so the call site stays free of an `android.content.res` import,
 * matching how the rest of this file consumes the `uiMode` int.
 */
private const val UI_MODE_NIGHT_MASK = 0x30
private const val UI_MODE_NIGHT_YES = 0x20
