package com.example.samplexrglimmer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.ListItem
import androidx.xr.glimmer.Text
import ee.schimke.composeai.preview.FocusedPreview

/**
 * Interactive XR navigation demo — a Glimmer menu captured as an animated GIF per environment
 * backdrop the design names (`docs/design/GLIMMER_PREVIEW.md` § "Data extension:
 * `:data-glimmer-environment-connector`"). Each frame shows focus on a different menu item;
 * the four-frame focus walk is the entire navigation signal.
 *
 * Glimmer's display model is **additive**: pure black pixels render as 100% transparent on-
 * device, lit pixels add light to whatever the wearer is looking at. The env compositor
 * module the design names will eventually be a post-render step, but until it exists the
 * sample paints both pieces inline:
 *
 *  1. The env backdrop ([EnvironmentBackdrop]) — a real photo for Dark / Busy /
 *     VeniceCanalCats and a procedurally-drawn Compose `Canvas` scene for Light, opaque RGB
 *     in both cases.
 *  2. The Glimmer UI on top — `GlimmerTheme` with `ListItem`s drawn on a `Color.Black`
 *     additive-zero base and composited with `BlendMode.Plus`, so the display *adds* its lit
 *     pixels onto the env exactly as on-device: black contributes nothing (the env shows
 *     through a `ListItem` untouched), the dark Glimmer surface tint brightens the env where
 *     items sit, and white text / focus borders add full light. When
 *     `:data-glimmer-environment-connector` lands the per-env [EnvironmentBackdrop] moves into
 *     the connector and the sample reverts to plain additive-RGB captures (Encoding B) on a
 *     black background — the `ADD`-blend then happens post-render on the captured PNG instead
 *     of inline.
 *
 * The four GIFs (Light / Dark / Busy / VeniceCanalCats) are **visually distinct today** because
 * the env compositing happens at render time — they're not pixel-identical placeholders. The
 * test asserts that explicitly so a future regression (e.g. an env backdrop accidentally falling
 * back to opaque black) surfaces here rather than in a downstream skill.
 *
 * The 1-D touchpad-gesture model is documented in SKILL.md § "Map input controls" but isn't
 * surfaced visually in these captures — Studio's own Glimmer previews don't paint a persistent
 * gesture chip onto the menu either, and inventing one for the sample would put a non-existent
 * affordance on screen. When `@GlimmerPreviewInput` + `:data-glimmer-input-connector` land the
 * planner's per-frame gesture override paints through that connector.
 *
 * Item count: four — the AI Glasses canvas (960×720dp at density 1.0) seats four `ListItem`s with 16-dp
 * inter-item spacing and 24-dp insets cleanly. A header chip over the menu was the first cut
 * but ate the focus ring on the fourth item; standalone chips live in the `NowPlayingCard`
 * sample.
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
    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ListItem(onClick = {}) { Text("Next track") }
      ListItem(onClick = {}) { Text("Previous track") }
      ListItem(onClick = {}) { Text("Add to favourites") }
      ListItem(onClick = {}) { Text("Send to phone") }
    }
  }
}

/**
 * Composites [content] (wrapped in `GlimmerTheme`) additively over [env]'s backdrop, reproducing
 * the on-device additive-display physics: real AI-Glasses displays *sum* lit pixels onto the
 * wearer's view, so pure black ≡ transparent and a dark surface tint ≡ a faint brightening.
 *
 * Two layers in a `Box`:
 *
 *  1. [EnvironmentBackdrop] — the opaque world the wearer is looking at.
 *  2. The Glimmer UI on a `Color.Black` additive-zero base, wrapped in
 *     `Modifier.drawWithContent { saveLayer(BlendMode.Plus) }` so the whole layer is `ADD`-
 *     blended onto the backdrop. Black pixels add nothing (the env shows through a `ListItem`
 *     untouched), the dark Glimmer surface tint brightens the env where items sit, and white
 *     text / focus borders add full light.
 *
 * `BlendMode.Plus` **does** survive Robolectric's hardware-rendering screenshot path — verified
 * empirically (an earlier revision of this sample wrongly assumed it was dropped and fell back to
 * an opaque "occluding-overlay" model, which read like Material 3 on a wallpaper rather than a
 * transparent HUD overlay). The `saveLayer` is what makes the add correct: it flattens the black
 * base + Glimmer UI into one buffer first, then adds that buffer to the env in a single step, so
 * overlapping translucent Glimmer draws don't double-count against the backdrop.
 *
 * When `:data-glimmer-environment-connector` lands, this inline `ADD`-blend moves to a post-render
 * pass over the captured PNG and `GlimmerEnvSurface` reverts to the plain `Color.Black` base of
 * the static [NowPlayingCard] previews (Encoding B in `docs/design/GLIMMER_PREVIEW.md`).
 */
@Composable
private fun GlimmerEnvSurface(env: GlimmerEnvironment, content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize()) {
    EnvironmentBackdrop(env, modifier = Modifier.fillMaxSize())
    Box(
      Modifier.fillMaxSize()
        .drawWithContent {
          drawIntoCanvas { canvas ->
            val paint = Paint().apply { blendMode = BlendMode.Plus }
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
          }
        }
        .background(Color.Black)
        .padding(24.dp)
    ) {
      GlimmerTheme(content = content)
    }
  }
}

/**
 * Backdrop per env. Mixes two source modes:
 *
 *  - **Dark / Busy / VeniceCanalCats**: bitmap drawables in `res/drawable-nodpi/` (Unsplash
 *    License photos cropped to 960×720 to match the AI Glasses 4:3 canvas — 960×720 dp at the
 *    calibrated density 1.0, see `AI_GLASSES_DEVICE_SPEC`).
 *    Drawn via `Image(painter = painterResource(...), contentScale = ContentScale.Crop)` so
 *    the photo fills the box at any device size.
 *  - **Light**: still procedurally drawn (sky gradient + grass + sun). No clean Unsplash photo
 *    for the bright-outdoor preset landed in the first asset pass; swapping in a JPEG when one
 *    arrives is one `Image(painterResource(R.drawable.env_light))` line. The procedural
 *    fallback at least keeps the four envs visually distinct.
 *
 * `drawable-nodpi/` (not `drawable/`) so AGP doesn't bake density-specific variants — the
 * AI Glasses preview always renders at a fixed canvas size and the renderer doesn't carry
 * a density qualifier when resolving resources.
 */
@Composable
private fun EnvironmentBackdrop(env: GlimmerEnvironment, modifier: Modifier = Modifier) {
  when (env) {
    GlimmerEnvironment.Light -> Canvas(modifier = modifier) { drawLight() }
    GlimmerEnvironment.Dark ->
      Image(
        painter = painterResource(R.drawable.env_dark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
      )
    GlimmerEnvironment.Busy ->
      Image(
        painter = painterResource(R.drawable.env_busy),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
      )
    GlimmerEnvironment.VeniceCanalCats ->
      Image(
        painter = painterResource(R.drawable.env_venice_canal_cats),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
      )
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
