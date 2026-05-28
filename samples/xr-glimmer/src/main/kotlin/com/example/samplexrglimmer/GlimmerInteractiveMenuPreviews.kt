package com.example.samplexrglimmer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import androidx.xr.glimmer.TitleChip
import ee.schimke.composeai.preview.FocusedPreview
import kotlin.random.Random

/**
 * Interactive XR navigation demo — a Glimmer menu plus a touchpad-gesture overlay, captured as
 * an animated GIF per environment backdrop the design names (`docs/design/GLIMMER_PREVIEW.md`
 * § "Data extension: `:data-glimmer-environment-connector`"). Each frame shows focus on one
 * menu item; the bottom-edge "▲ swipe up" affordance is what drove the transition.
 *
 * Glimmer's display model is **additive**: pure black pixels render as 100% transparent on-
 * device, lit pixels add light to whatever the wearer is looking at. The env compositor
 * module the design names will eventually be a post-render step, but until it exists the
 * sample paints both pieces inline:
 *
 *  1. The env backdrop ([EnvironmentBackdrop]) — a procedurally-drawn Compose `Canvas`
 *     scene per env (sky / city / market / canal), opaque RGB.
 *  2. The Glimmer UI on top, wrapped in a `graphicsLayer` with `BlendMode.Plus` so the
 *     `Color.Black` base from `GlimmerSurface` adds zero (env shows through unchanged) and
 *     lit pixels (TitleChip primary, ListItem surface tint, focus ring, Text white) add to
 *     the env — which is exactly the physics of an additive display. When
 *     `:data-glimmer-environment-connector` lands the per-env [EnvironmentBackdrop] +
 *     additive-blend wrapper here move into the connector and the sample reverts to plain
 *     additive-RGB captures (Encoding B) on a black background.
 *
 * The four GIFs (Light / Dark / Busy / VeniceCanalCats) are **visually distinct today** because
 * the env compositing happens at render time — they're not pixel-identical placeholders. The
 * test asserts that explicitly so a future regression (e.g. an env backdrop accidentally falling
 * back to opaque black) surfaces here rather than in a downstream skill.
 *
 * Glimmer's input model is **1-D** (one finger on the touchpad, axis is contextual), so a
 * static swipe-up indicator pinned to the bottom is the honest representation across the whole
 * sequence: every step is one swipe. A future `@GlimmerPreviewInput` + `:data-glimmer-input-
 * connector` will replace the hand-rolled overlay with a planner-driven `AroundComposable`
 * reading `renderNow.overrides.glimmerInput`; at that point the gesture per frame becomes
 * dynamic (`▲ Next` / `▼ Previous` / `● Tap` / `↺ Back`) and the indicator below moves into
 * the connector.
 *
 * Item count: four — the AI Glasses canvas (640×480dp) seats four `ListItem`s plus the bottom
 * gesture indicator with ~14dp of slack at 16-dp inter-item spacing. A header chip over the
 * menu was the first cut but pushed the fourth item under the indicator and ate the focus ring
 * on frame 4; standalone chips live in the `NowPlayingCard` sample.
 *
 * Discovery side: `@FocusedPreview` flips `LocalInputModeManager` to Keyboard so Compose's
 * focusable system honours the renderer's `moveFocus(...)` calls — Robolectric's host
 * environment is permanently Touch otherwise and Glimmer's `ListItem(onClick)` focusables
 * would refuse focus. `indices` mode lands one capture per focus index; `gif = true` stitches
 * the captures into a single `<basename>.gif` instead of writing four PNG siblings.
 *
 * One top-level function per env so discovery generates a distinct `PreviewInfo.id` per
 * capture (a single function with four stacked `@Preview` annotations would land four captures
 * that all render the same content because the composable can't read its own preview name to
 * pick an env). Each function delegates to [InteractiveMenuOnEnv] with the env value.
 */
@Preview(name = "Light", device = AI_GLASSES_DEVICE_SPEC)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuLight() = InteractiveMenuOnEnv(GlimmerEnvironment.Light)

@Preview(name = "Dark", device = AI_GLASSES_DEVICE_SPEC)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuDark() = InteractiveMenuOnEnv(GlimmerEnvironment.Dark)

@Preview(name = "Busy", device = AI_GLASSES_DEVICE_SPEC)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuBusy() = InteractiveMenuOnEnv(GlimmerEnvironment.Busy)

@Preview(name = "VeniceCanalCats", device = AI_GLASSES_DEVICE_SPEC)
@FocusedPreview(indices = [0, 1, 2, 3], gif = true)
@Composable
fun GlimmerXrMenuVeniceCanalCats() = InteractiveMenuOnEnv(GlimmerEnvironment.VeniceCanalCats)

@Composable
private fun InteractiveMenuOnEnv(env: GlimmerEnvironment) {
  GlimmerEnvSurface(env) {
    Box(Modifier.fillMaxSize()) {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        ListItem(onClick = {}) { Text("Next track") }
        ListItem(onClick = {}) { Text("Previous track") }
        ListItem(onClick = {}) { Text("Add to favourites") }
        ListItem(onClick = {}) { Text("Send to phone") }
      }

      // XR touchpad-gesture indicator pinned to the bottom-centre. Mirrors the design
      // doc's `:data-glimmer-input-connector` arrow-glyph affordance — the same place an
      // overlay extension would paint, just baked into the composable until that connector
      // module exists. Drawn through the additive layer so it reads as lit-light against
      // any env backdrop.
      XrTouchpadGestureIndicator(
        gesture = XrGesture.SwipeUp,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
      )
    }
  }
}

/**
 * Wraps [content] in `GlimmerTheme` on top of [env]'s procedurally-drawn backdrop. No
 * `Color.Black` base layer — Glimmer's own surface tokens (`Card`, `ListItem`, `TitleChip` all
 * paint their own surface fill) cover the env where the UI sits, and the empty space between
 * elements lets the env show through.
 *
 * **Compromise vs. on-device physics.** Real additive AI-Glasses displays additively sum lit
 * pixels onto the wearer's view (black ≡ transparent). Reproducing that in Compose needs
 * `Modifier.drawWithContent { saveLayer + BlendMode.Plus }` around the Glimmer UI on top of an
 * opaque-black base — Robolectric's hardware-rendering screenshot path drops the blend mode
 * silently (verified empirically: the resulting captures collapsed to identical opaque-black
 * frames regardless of env). Until we either (a) plumb a software-rendering option through
 * `RoborazziComposeOptions` or (b) move the env composite into the post-render
 * `:data-glimmer-environment-connector` (where it operates on the captured PNG and `ADD`-
 * blending is a per-pixel arithmetic step we control), the sample picks the simpler
 * "occluding-overlay" model: lit Glimmer surfaces cover the env where they sit, empty space
 * shows the env. Loses the additive-light illusion (a dark Card on a bright Light env reads
 * more like Material 3 on a wallpaper than like a HUD overlay) but the user can see the env
 * actually differing per variant, which is the point of having per-env names.
 */
@Composable
private fun GlimmerEnvSurface(env: GlimmerEnvironment, content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize()) {
    EnvironmentBackdrop(env, modifier = Modifier.fillMaxSize())
    Box(Modifier.fillMaxSize().padding(24.dp)) { GlimmerTheme(content = content) }
  }
}

/**
 * Procedurally-drawn Compose backdrop per env. Kept inline (no resource assets) so the sample
 * has zero asset deps — Studio's docs name Light / Dark / Busy as their contrast-test backdrops
 * and the design adds VeniceCanalCats as the "delight" preset; each scene below is a
 * recognisable approximation rather than a pixel-faithful asset.
 */
@Composable
private fun EnvironmentBackdrop(env: GlimmerEnvironment, modifier: Modifier = Modifier) {
  Canvas(modifier = modifier) {
    when (env) {
      GlimmerEnvironment.Light -> drawLight()
      GlimmerEnvironment.Dark -> drawDark()
      GlimmerEnvironment.Busy -> drawBusy()
      GlimmerEnvironment.VeniceCanalCats -> drawVeniceCanalCats()
    }
  }
}

private fun DrawScope.drawLight() {
  drawRect(
    brush = Brush.verticalGradient(listOf(Color(0xFFAEDDFF), Color(0xFFD2EAFF))),
    size = size,
  )
  val hillY = size.height * 0.75f
  drawRect(
    color = Color(0xFF98C778),
    topLeft = Offset(0f, hillY),
    size = Size(size.width, size.height - hillY),
  )
  drawCircle(color = Color(0xFFFFE599), radius = 36f, center = Offset(size.width - 100f, 100f))
}

private fun DrawScope.drawDark() {
  drawRect(
    brush = Brush.verticalGradient(listOf(Color(0xFF0A0F1A), Color(0xFF14213C))),
    size = size,
  )
  val skylineY = size.height * 0.45f
  val cols = 6
  val colW = size.width / cols
  for (i in 0 until cols) {
    val h = (size.height - skylineY) * (0.55f + (i % 3) * 0.18f)
    drawRect(
      color = Color(0xFF1A1F30),
      topLeft = Offset(i * colW, size.height - h),
      size = Size(colW + 4f, h),
    )
  }
  val rng = Random(42)
  repeat(36) {
    val x = rng.nextFloat() * size.width
    val y = skylineY + rng.nextFloat() * (size.height - skylineY) * 0.85f
    drawRect(color = Color(0xFFFFD050), topLeft = Offset(x, y), size = Size(8f, 12f))
  }
  repeat(40) {
    val x = rng.nextFloat() * size.width
    val y = rng.nextFloat() * skylineY * 0.8f
    drawCircle(color = Color.White, radius = 1.5f, center = Offset(x, y))
  }
}

private fun DrawScope.drawBusy() {
  drawRect(color = Color(0xFFB89870), size = size)
  val awningH = size.height * 0.18f
  val stripeW = size.width / 8
  val awningColors =
    listOf(Color(0xFFD63838), Color(0xFFE6C850), Color(0xFF388CC2), Color(0xFF5AB060))
  for (i in 0..8) {
    drawRect(
      color = awningColors[i % 4],
      topLeft = Offset(i * stripeW, 0f),
      size = Size(stripeW + 4f, awningH),
    )
  }
  val rng = Random(7)
  repeat(60) {
    val x = rng.nextFloat() * size.width
    val y = size.height * 0.4f + rng.nextFloat() * size.height * 0.55f
    val r = 16f + rng.nextFloat() * 24f
    drawCircle(color = Color(0xFF8B5C40), radius = r, center = Offset(x, y), alpha = 0.65f)
  }
}

private fun DrawScope.drawVeniceCanalCats() {
  drawRect(
    brush = Brush.verticalGradient(listOf(Color(0xFFFFB8A0), Color(0xFFFFD8C0))),
    size = size,
  )
  val buildY = size.height * 0.5f
  val rng = Random(11)
  var x = 0f
  while (x < size.width) {
    val w = 60f + rng.nextFloat() * 50f
    val h = 100f + rng.nextFloat() * 80f
    val facade = Color(0xFFD8A088).copy(alpha = 0.85f + rng.nextFloat() * 0.15f)
    drawRect(color = facade, topLeft = Offset(x, buildY - h), size = Size(w, h))
    x += w + rng.nextFloat() * 8f
  }
  drawRect(
    color = Color(0xFF5A8090),
    topLeft = Offset(0f, buildY),
    size = Size(size.width, size.height - buildY),
  )
  // Two gondolas (dark arcs) with cat-head silhouettes.
  drawArc(
    color = Color(0xFF1A2A30),
    startAngle = 0f,
    sweepAngle = 180f,
    useCenter = true,
    topLeft = Offset(size.width * 0.12f, size.height * 0.78f),
    size = Size(160f, 36f),
  )
  drawCircle(
    color = Color(0xFFE6964A),
    radius = 20f,
    center = Offset(size.width * 0.20f, size.height * 0.72f),
  )
  drawArc(
    color = Color(0xFF1A2A30),
    startAngle = 0f,
    sweepAngle = 180f,
    useCenter = true,
    topLeft = Offset(size.width * 0.58f, size.height * 0.82f),
    size = Size(180f, 40f),
  )
  drawCircle(
    color = Color(0xFFE6964A),
    radius = 24f,
    center = Offset(size.width * 0.67f, size.height * 0.75f),
  )
}

/**
 * Pill-shaped indicator showing which 1-D touchpad gesture the wearer would use to advance.
 * Drawn as a Glimmer `TitleChip` with the gesture glyph + label so it picks up the theme's
 * pill shape, surface tint, and outline border without us having to recreate that styling
 * by hand. The chip is non-interactive — purely a visual annotation on the capture.
 */
@Composable
private fun XrTouchpadGestureIndicator(gesture: XrGesture, modifier: Modifier = Modifier) {
  Box(modifier = modifier) {
    TitleChip {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(gesture.glyph)
        Spacer(Modifier.width(8.dp))
        Text(gesture.label)
      }
    }
  }
}

/**
 * Environment backdrops the interactive demo composites against. Matches the per-env names the
 * design doc and the `NowPlayingCard` sample's `@Preview` family already use — Studio's
 * Light / Dark / Busy contrast presets plus the VeniceCanalCats delight scene.
 */
internal enum class GlimmerEnvironment {
  Light,
  Dark,
  Busy,
  VeniceCanalCats,
}

/**
 * The 1-D touchpad gestures Glimmer's input model exposes — see SKILL.md § "Map input
 * controls". Kept as an enum so a future `:data-glimmer-input-connector` can encode the
 * planner's override directly as one of these and the overlay reads the field with no
 * string-parsing in between. `Tap` and `Back` aren't exercised by the GIFs above but are
 * here for the eventual dynamic-gesture variant.
 */
internal enum class XrGesture(val glyph: String, val label: String) {
  SwipeUp(glyph = "▲", label = "swipe up"),
  SwipeDown(glyph = "▼", label = "swipe down"),
  Tap(glyph = "●", label = "tap"),
  Back(glyph = "↺", label = "back"),
}
